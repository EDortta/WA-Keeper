package br.com.wanotifkeeper

import android.app.Notification
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class NotifListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val watchedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"  // WhatsApp Business também, por precaução
    )

    @Volatile private var lastPurge = 0L

    private val motion by lazy { MotionDetector(applicationContext) }
    private val callDetector by lazy { CallDetector(applicationContext) { flushPendingAfterCall() } }
    // TTS e player de áudio só são criados quando há algo a reproduzir — sem segurar recursos à toa.
    private var speaker: Speaker? = null
    private var audioPlayer: AudioPlayer? = null
    private var beeper: Beeper? = null

    // Mensagens que chegariam por voz durante uma ligação: em vez de falar por cima da
    // chamada, avisamos com um beep e lemos/tocamos tudo assim que ela terminar.
    private val pendingDuringCall = mutableListOf<PendingPlayback>()

    private sealed class PendingPlayback {
        data class Text(val sender: String, val text: String) : PendingPlayback()
        data class Audio(val path: String) : PendingPlayback()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        motion.start()
        callDetector.start(scope)
        scope.launch { runPurge() }
    }

    override fun onListenerDisconnected() {
        motion.stop()
        callDetector.stop()
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
