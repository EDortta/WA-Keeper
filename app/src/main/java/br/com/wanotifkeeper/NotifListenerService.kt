package br.com.wanotifkeeper

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class NotifListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "WAK-ReplyAction"
        private const val TAG_VOICE_GATE = "WAK-VoiceGate"
        private const val DEDUP_WINDOW_MS = 2000L

        /** Menor SDK em que SpeechRecognizer.createOnDeviceSpeechRecognizer existe. */
        private const val MIN_SDK_VOICE_COMMANDS = Build.VERSION_CODES.S // 31
        private const val VOICE_GATE_CHECK_MS = 7000L
        private const val VOICE_CHANNEL_ID = "voice_commands"
        private const val NOTIF_ID_VOICE_LISTENING = 42
    }

    private val watchedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"  // WhatsApp Business também, por precaução
    )

    @Volatile private var lastPurge = 0L

    private val motion by lazy { MotionDetector(applicationContext) }
    private val callDetector by lazy {
        CallDetector(applicationContext) {
            flushPendingAfterCall()
            updateListeningState()
        }
    }
    // TTS e player de áudio só são criados quando há algo a reproduzir — sem segurar recursos à toa.
    private var speaker: Speaker? = null
    private var audioPlayer: AudioPlayer? = null
    private var beeper: Beeper? = null

    // Janela de ativação dos comandos de voz: microfone só liga aqui dentro (ver runVoiceGateLoop).
    @Volatile private var voiceListening = false

    private val voiceEngine by lazy {
        VoiceCommandEngine(
            context = applicationContext,
            scope = scope,
            dao = NotifDatabase.get(applicationContext).dao(),
            say = { text -> sayPrompt(text) },
            announce = { sender, text -> speak(sender, text) },
            isSpeakerBusy = { speaker?.isBusy() ?: false },
            defaultAccountPkg = { Prefs.voiceDefaultAccountPkg(applicationContext) },
            onSpeechPackMissing = {
                Prefs.setSpeechPackMissing(applicationContext, true)
                android.util.Log.d(TAG_VOICE_GATE, "pacote de voz pt-BR indisponível — comandos desligados nesta sessão")
            },
            onRecognitionWorking = {
                if (Prefs.isSpeechPackMissing(applicationContext)) {
                    Prefs.setSpeechPackMissing(applicationContext, false)
                }
            }
        )
    }

    // Mensagens que chegariam por voz durante uma ligação: em vez de falar por cima da
    // chamada, avisamos com um beep e lemos/tocamos tudo assim que ela terminar.
    private val pendingDuringCall = mutableListOf<PendingPlayback>()

    private sealed class PendingPlayback {
        data class Text(val sender: String, val text: String) : PendingPlayback()
        data class Audio(val path: String) : PendingPlayback()
    }

    // Ação de "resposta rápida" que o próprio WhatsApp publica na notificação — guardada
    // pra um futuro comando de voz reenviar sem precisar de API nenhuma do WhatsApp.
    // Só em memória: um PendingIntent não sobrevive (nem faria sentido sobreviver) a um
    // reinício do processo. Chave "pacote|remetente" pra não confundir contas.
    private val replyActions = ConcurrentHashMap<String, CachedReply>()

    private class CachedReply(
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val resultKey: String,
        val notificationKey: String
    )

    private val recentPosts = ConcurrentHashMap<String, Long>()

    /** true se pacote+remetente+texto idênticos já passaram por aqui há pouco (repost do WhatsApp). */
    private fun isDuplicateRepost(pkg: String, sender: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        recentPosts.entries.removeAll { now - it.value > DEDUP_WINDOW_MS }
        val lastSeen = recentPosts.put("$pkg|$sender|$text", now)
        return lastSeen != null && now - lastSeen < DEDUP_WINDOW_MS
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        motion.start()
        callDetector.start(scope)
        scope.launch { runPurge() }
        scope.launch { runVoiceGateLoop() }
    }

    override fun onListenerDisconnected() {
        motion.stop()
        callDetector.stop()
        stopVoiceListening()
        speaker?.shutdown()
        speaker = null
        audioPlayer?.shutdown()
        audioPlayer = null
        beeper?.shutdown()
        beeper = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        motion.stop()
        callDetector.stop()
        stopVoiceListening()
        speaker?.shutdown()
        speaker = null
        audioPlayer?.shutdown()
        audioPlayer = null
        beeper?.shutdown()
        beeper = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in watchedPackages) return

        val extras = sbn.notification.extras ?: return
        val rawTitle = extras.getString(Notification.EXTRA_TITLE) ?: return
        // BigTextStyle traz o texto completo; EXTRA_TEXT vem truncado.
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return

        // Remover prefixo "WhatsApp: " que aparece em algumas notificações de grupo
        val title = rawTitle.removePrefix("WhatsApp: ").trim()

        if (NoiseFilter.isNoise(title, text)) return

        // O WhatsApp costuma repostar/atualizar a mesma notificação poucos ms depois, com
        // texto idêntico — sem isso, toda mensagem seria lida em voz alta e guardada em dobro.
        if (isDuplicateRepost(sbn.packageName, title, text.trim())) return

        cacheReplyAction(sbn, title)

        val isVoice = MediaVault.looksLikeVoiceMessage(text)

        // Leitura em voz alta do TEXTO: só quando em movimento, com o switch da conta ligado,
        // e apenas para mensagens de texto — mensagem de voz é tratada pelo player de áudio.
        if (!isVoice && Prefs.isTtsEnabled(applicationContext, sbn.packageName) && motion.isInMotion()) {
            if (callDetector.isInCall()) {
                synchronized(pendingDuringCall) { pendingDuringCall.add(PendingPlayback.Text(title, text.trim())) }
                beeper().beep()
            } else {
                speak(title, text.trim())
            }
        }

        val picture = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }.getOrNull()

        scope.launch {
            val imagePath = picture?.let { savePicture(it, sbn.postTime) }
            val db = NotifDatabase.get(applicationContext)
            val rowId = db.dao().insert(
                NotifEntity(
                    sender = title,
                    text = text.trim(),
                    timestamp = sbn.postTime,
                    packageName = sbn.packageName,
                    imagePath = imagePath
                )
            )
            if (isVoice &&
                Prefs.isAudioCaptureEnabled(applicationContext, sbn.packageName) &&
                !Prefs.isAudioBlocked(applicationContext, title)
            ) {
                captureAudio(rowId, sbn.packageName, sbn.postTime)
            }
            runPurge()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in watchedPackages) invalidateReplyAction(sbn.key)
    }

    /**
     * Guarda a ação de resposta rápida (se a notificação trouxer uma) pra essa conversa.
     * Notificação mais nova sempre substitui a mais velha; se esta em particular não trouxer
     * a ação (acontece em reposts de atualização), a entrada anterior é mantida como está.
     */
    private fun cacheReplyAction(sbn: StatusBarNotification, sender: String) {
        val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return
        val remoteInputs = action.remoteInputs ?: return
        val actionIntent = action.actionIntent ?: return
        replyActions["${sbn.packageName}|$sender"] = CachedReply(
            actionIntent = actionIntent,
            remoteInputs = remoteInputs,
            resultKey = remoteInputs.first().resultKey,
            notificationKey = sbn.key
        )
        android.util.Log.d(TAG, "Reply action cached for ${sbn.packageName}|$sender (key=${remoteInputs.first().resultKey})")
    }

    /** A notificação some (lida, apagada, substituída sem ação) — a resposta cacheada não vale mais. */
    private fun invalidateReplyAction(notificationKey: String) {
        val stale = replyActions.entries.firstOrNull { it.value.notificationKey == notificationKey }?.key ?: return
        replyActions.remove(stale)
    }

    /**
     * O arquivo de voz pode aparecer alguns instantes após a notificação (download);
     * tentamos algumas vezes com espera crescente até copiá-lo.
     */
    private suspend fun captureAudio(rowId: Long, pkg: String, postTime: Long) {
        for (wait in longArrayOf(300, 1200, 3000, 6000)) {
            delay(wait)
            val path = MediaVault.captureLatest(applicationContext, pkg, postTime) ?: continue
            NotifDatabase.get(applicationContext).dao().setAudioPath(rowId, path)
            if (Prefs.isAudioPlayInMotion(applicationContext) && motion.isInMotion()) {
                if (callDetector.isInCall()) {
                    synchronized(pendingDuringCall) { pendingDuringCall.add(PendingPlayback.Audio(path)) }
                    beeper().beep()
                } else {
                    player().play(path)
                }
            }
            return
        }
    }

    private fun player(): AudioPlayer =
        audioPlayer ?: AudioPlayer().also { audioPlayer = it }

    private fun speak(sender: String, text: String) {
        val s = speaker ?: Speaker(applicationContext).also { speaker = it }
        s.announce(sender, text)
    }

    /** Avisos/perguntas do motor de comandos de voz — sem prefixo de remetente. */
    private fun sayPrompt(text: String) {
        val s = speaker ?: Speaker(applicationContext).also { speaker = it }
        s.say(text)
    }

    private fun beeper(): Beeper =
        beeper ?: Beeper().also { beeper = it }

    /** Chamada termina: o que foi apenas avisado com beep agora é lido/tocado, na ordem de chegada. */
    private fun flushPendingAfterCall() {
        val items = synchronized(pendingDuringCall) {
            val copy = pendingDuringCall.toList()
            pendingDuringCall.clear()
            copy
        }
        items.forEach { item ->
            when (item) {
                is PendingPlayback.Text -> speak(item.sender, item.text)
                is PendingPlayback.Audio -> player().play(item.path)
            }
        }
    }

    /** Enquanto conectado, decide a cada [VOICE_GATE_CHECK_MS] se a janela de ativação está aberta. */
    private suspend fun runVoiceGateLoop() {
        while (scope.isActive) {
            updateListeningState()
            delay(VOICE_GATE_CHECK_MS)
        }
    }

    /**
     * Abre a escuta por movimento (já em carro, mesmo sinal do TTS) OU por timer manual — mas
     * nunca durante uma ligação. O timer manual libera a escuta mesmo parado (ex.: testar
     * sentado no carro); porém, assim que o movimento é observado e depois cessa, o timer é
     * cortado mesmo com tempo restante — corte de segurança para não continuar ouvindo depois
     * que o carro para.
     */
    private fun updateListeningState() {
        val enabled = Prefs.isVoiceCommandsEnabled(applicationContext) &&
            Build.VERSION.SDK_INT >= MIN_SDK_VOICE_COMMANDS &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val shouldListen = enabled && !callDetector.isInCall() && voiceGateOpen()

        if (shouldListen && !voiceListening) startVoiceListening()
        else if (!shouldListen && voiceListening) stopVoiceListening()
    }

    private fun voiceGateOpen(): Boolean {
        val motionOpen = motion.isInMotion()
        val now = System.currentTimeMillis()
        var manualOpen = Prefs.manualListenUntil(applicationContext) > now

        if (manualOpen) {
            if (motionOpen) {
                Prefs.setManualTimerMotionSeen(applicationContext, true)
            } else if (Prefs.manualTimerMotionSeen(applicationContext)) {
                // Movimento aconteceu durante a janela manual e já parou — corta o timer.
                Prefs.setManualListenUntil(applicationContext, 0L)
                manualOpen = false
            }
        }
        return motionOpen || manualOpen
    }

    private fun startVoiceListening() {
        voiceListening = true
        startForeground(NOTIF_ID_VOICE_LISTENING, buildListeningNotification())
        voiceEngine.start()
        android.util.Log.d(TAG_VOICE_GATE, "ON (motion=${motion.isInMotion()}, manualUntil=${Prefs.manualListenUntil(applicationContext)})")
    }

    private fun stopVoiceListening() {
        if (!voiceListening) return
        voiceListening = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        voiceEngine.stop()
        android.util.Log.d(TAG_VOICE_GATE, "OFF")
    }

    private fun buildListeningNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(VOICE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(VOICE_CHANNEL_ID, "Comandos de voz", NotificationManager.IMPORTANCE_LOW)
                    .apply { setSound(null, null) }
            )
        }
        return NotificationCompat.Builder(applicationContext, VOICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("WA Keeper ouvindo comandos de voz")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** Guarda a imagem embutida na notificação — sem depender do WhatsApp depois. */
    private fun savePicture(bmp: Bitmap, postTime: Long): String? = runCatching {
        val file = File(Retention.imageDir(applicationContext), "img-$postTime-${bmp.hashCode()}.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.absolutePath
    }.getOrNull()

    /** Retenção rodada no máximo uma vez por hora para não pesar em rajadas. */
    private suspend fun runPurge() {
        val now = System.currentTimeMillis()
        if (now - lastPurge < RetentionPolicy.HOUR_MS) return
        lastPurge = now
        runCatching { Retention.purge(applicationContext, now) }
    }
}
