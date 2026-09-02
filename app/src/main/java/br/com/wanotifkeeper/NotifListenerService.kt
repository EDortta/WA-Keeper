package br.com.wanotifkeeper

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class NotifListenerService : NotificationListenerService() {

    // Sem o handler, uma exceção não tratada num filho de `launch` sobe para o handler de
    // thread padrão e derruba o app — SupervisorJob protege os irmãos, não o processo.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
    )

    companion object {
        private const val TAG = "WAK-ReplyAction"
        private const val TAG_VOICE_GATE = "WAK-VoiceGate"
        private const val DEDUP_WINDOW_MS = 2000L

        /**
         * Quanto o repost que traz a imagem espera pelo rowId da primeira notificação.
         * O INSERT roda em coroutine, então a segunda notificação pode chegar antes de a
         * linha existir; sem espera, a imagem se perderia por uma corrida de milissegundos.
         */
        private const val REPOST_ROWID_WAIT_MS = 5000L

        /** Menor SDK em que SpeechRecognizer.createOnDeviceSpeechRecognizer existe. */
        private const val MIN_SDK_VOICE_COMMANDS = Build.VERSION_CODES.S // 31
        private const val VOICE_GATE_CHECK_MS = 7000L
        private const val VOICE_CHANNEL_ID = "voice_commands"
        private const val NOTIF_ID_VOICE_LISTENING = 42

        /** Distância máxima entre o horário da mensagem do MessagingStyle e o postTime. */
        private const val MESSAGE_TS_TOLERANCE_MS = 60_000L
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

    // Qual pedido do botão de microfone já foi armado. Guardado por VALOR (o instante-limite do
    // pedido) e não por booleano: assim um toque novo, que grava um limite novo, rearma; e os
    // tiques do gate dentro da mesma janela não rearmam.
    @Volatile private var directCommandArmedFor = 0L

    // Sem isso, desligar o switch em Ajustes só surtia efeito no próximo tick do
    // runVoiceGateLoop (até VOICE_GATE_CHECK_MS depois) — o microfone/beep continuava indo
    // nesse meio-tempo, e era fácil confundir com o recognizer não desligando de verdade.
    // Referência forte de propósito: registerOnSharedPreferenceChangeListener só guarda uma
    // fraca, um listener só em lambda local seria coletado e pararia de disparar.
    private val voicePrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_VOICE_COMMANDS_ENABLED || key == Prefs.KEY_DIRECT_COMMAND_UNTIL) {
                android.util.Log.d(TAG_VOICE_GATE, "$key mudou — reavaliando na hora")
                scope.launch { updateListeningState() }
            }
        }

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

    private val repostGuard = RepostGuard()

    /** Copia a imagem que veio na própria notificação (bitmap ou URI), se veio alguma. */
    private suspend fun imagePathFromNotification(
        picture: Bitmap?,
        messageImage: NotificationImage?,
        postTime: Long
    ): String? {
        picture?.let { bmp -> savePicture(bmp, postTime)?.let { return it } }
        if (messageImage != null) {
            return MediaVault.captureImageUri(
                applicationContext,
                messageImage.uri,
                messageImage.mimeType,
                postTime
            )
        }
        return null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Prefs.registerChangeListener(applicationContext, voicePrefsListener)
        motion.start()
        callDetector.start(scope)
        scope.launch { runPurge() }
        scope.launch { runVoiceGateLoop() }
    }

    override fun onListenerDisconnected() {
        Prefs.unregisterChangeListener(applicationContext, voicePrefsListener)
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
        Prefs.unregisterChangeListener(applicationContext, voicePrefsListener)
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

        // O resumo do grupo de notificações não é mensagem: é a linha que o WhatsApp publica
        // para representar as demais. Quando há UMA conversa com UMA mensagem não lida, ele
        // costuma repetir o próprio texto da mensagem em vez de "Novas mensagens: N" (que o
        // NoiseFilter pegaria) — e aí a mesma mensagem chega duas vezes, pelo resumo e pela
        // notificação filha, sendo lida em voz alta duas vezes e guardada duas vezes.
        // Confirmado no aparelho: `flags=GROUP_SUMMARY` com `android.title=WA Business`.
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = sbn.notification.extras ?: return
        val rawTitle = extras.getString(Notification.EXTRA_TITLE) ?: return
        // BigTextStyle traz o texto completo; EXTRA_TEXT vem truncado.
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return

        // Remover prefixo "WhatsApp: " que aparece em algumas notificações de grupo
        val title = rawTitle.removePrefix("WhatsApp: ").trim()

        if (NoiseFilter.isNoise(title, text)) return

        cacheReplyAction(sbn, title)

        val picture = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }.getOrNull()

        val messageImage = extractMessagingStyleImage(sbn.notification, sbn.postTime)
        // EXTRA_IS_GROUP_CONVERSATION é API 28 e o minSdk é 26; a chave é estável desde então
        // e ausente vira `false`, o lado seguro (não remove prefixo, não relaxa a regra).
        val isGroup = extras.getBoolean("android.isGroupConversation", false)
        val isImage = picture != null || messageImage != null ||
            MediaVault.looksLikeImageMessage(text, isGroup)
        val isVoice = MediaVault.looksLikeVoiceMessage(text)

        // A extração da imagem vem ANTES do dedup de propósito: a segunda notificação — a que
        // o WhatsApp publica com o bitmap/URI, texto idêntico — precisa ser reconhecida como
        // "repost que traz imagem" e não como repost qualquer. Ver Decision.AttachImage.
        //
        // E o dedup inteiro vem ANTES de falar e de guardar, que é o que impede a mesma
        // mensagem de ser lida em voz alta duas vezes.
        val decision = repostGuard.classify(
            packageName = sbn.packageName,
            sender = title,
            text = text.trim(),
            messageTime = lastMessageTimestamp(sbn.notification),
            hasImageContent = picture != null || messageImage != null,
            now = System.currentTimeMillis()
        )
        val record = when (decision) {
            is RepostGuard.Decision.Drop -> return
            is RepostGuard.Decision.AttachImage -> {
                scope.launch {
                    val rowId = withTimeoutOrNull(REPOST_ROWID_WAIT_MS) {
                        decision.record.rowId.await()
                    } ?: return@launch
                    val path = imagePathFromNotification(picture, messageImage, sbn.postTime)
                    if (path != null) {
                        NotifDatabase.get(applicationContext).dao().setImagePath(rowId, path)
                    } else {
                        captureImage(rowId, sbn.packageName, sbn.postTime)
                    }
                }
                return
            }
            is RepostGuard.Decision.New -> decision.record
        }

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

        scope.launch {
            val imagePath = imagePathFromNotification(picture, messageImage, sbn.postTime)

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
            record.rowId.complete(rowId)

            // Algumas versões do WhatsApp não expõem bitmap/URI na notificação.
            // Nesses casos tentamos copiar a imagem recém-baixada do diretório oficial de mídia.
            // Em coroutine própria: o retry de imagem espera até 10,5 s quando não acha nada,
            // e não pode empurrar a captura de ÁUDIO para depois disso — quanto mais tarde ela
            // começa, mais chance o WhatsApp tem de apagar o .opus ("apagar para todos").
            // Sai na frente do gancho da EPIC 4 pelo mesmo motivo: é o caminho sensível a tempo.
            val imageJob = if (imagePath == null && isImage) {
                scope.launch { captureImage(rowId, sbn.packageName, sbn.postTime) }
            } else null

            // EPIC 4 (#18): a mensagem recebida já foi persistida acima — só depois
            // disso a mensagem armada para esta conversa pode disparar. Toda a lógica
            // mora em ScheduledMessageTrigger; aqui é só o gancho.
            runCatching { ScheduledMessageTrigger.onIncoming(applicationContext, sbn, title) }

            if (isVoice &&
                Prefs.isAudioCaptureEnabled(applicationContext, sbn.packageName) &&
                !Prefs.isAudioBlocked(applicationContext, title)
            ) {
                captureAudio(rowId, sbn.packageName, sbn.postTime)
            }
            // A retenção varre órfãos comparando com os caminhos gravados no banco; só pode
            // rodar depois que a captura de imagem tiver registrado o dela.
            imageJob?.join()
            runPurge()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in watchedPackages) ReplyActionRegistry.forget(sbn.key)
    }

    /** Delegado ao ReplyActionRegistry — o cache vive fora do serviço para o envio alcançá-lo. */
    private fun cacheReplyAction(sbn: StatusBarNotification, sender: String) {
        val resultKey = ReplyActionRegistry.remember(
            packageName = sbn.packageName,
            sender = sender,
            notificationKey = sbn.key,
            actions = sbn.notification.actions
        ) ?: return
        android.util.Log.d(TAG, "Reply action cached for ${sbn.packageName}|$sender (key=$resultKey)")
    }

    /**
     * MessagingStyle pode carregar uma URI da imagem, mesmo quando EXTRA_PICTURE não existe.
     */
    /**
     * Horário da última mensagem do `MessagingStyle` — a identidade estável que o
     * [RepostGuard] usa. Não muda entre reposts da mesma conversa, e é diferente para cada
     * mensagem, então distingue "o WhatsApp repostou" de "chegou outra mensagem".
     */
    private fun lastMessageTimestamp(notification: Notification): Long? = runCatching {
        @Suppress("DEPRECATION")
        val bundles = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: return null
        Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            .lastOrNull()?.timestamp?.takeIf { it > 0L }
    }.getOrNull()

    private fun extractMessagingStyleImage(notification: Notification, postTime: Long): NotificationImage? = runCatching {
        @Suppress("DEPRECATION")
        val bundles = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        // EXTRA_MESSAGES traz o HISTÓRICO recente da conversa, não só a mensagem que disparou
        // esta notificação. Procurar "a última COM imagem" fazia uma mensagem de TEXTO herdar
        // a foto anterior da mesma conversa — exatamente o que a #17 proíbe. Só a última
        // entrada do bundle é a desta notificação; se ela não for imagem, não há imagem.
        val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            .lastOrNull() ?: return null
        val uri = last.dataUri ?: return null
        if (last.dataMimeType?.startsWith("image/", ignoreCase = true) != true) return null
        // Segunda trava: mesmo sendo a última, se o horário dela está longe do postTime é
        // histórico, não a mensagem que chegou agora.
        if (last.timestamp > 0L && kotlin.math.abs(postTime - last.timestamp) > MESSAGE_TS_TOLERANCE_MS) {
            return null
        }
        NotificationImage(uri, last.dataMimeType)
    }.getOrNull()

    /**
     * A imagem pode aparecer alguns instantes após a notificação; tentamos algumas vezes.
     */
    private suspend fun captureImage(rowId: Long, pkg: String, postTime: Long) {
        for (wait in MediaVault.IMAGE_RETRY_DELAYS_MS) {
            delay(wait)
            val path = MediaVault.captureLatestImage(applicationContext, pkg, postTime) ?: continue
            NotifDatabase.get(applicationContext).dao().setImagePath(rowId, path)
            return
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
        val masterEnabled = Prefs.isVoiceCommandsEnabled(applicationContext)
        val sdkSupported = Build.VERSION.SDK_INT >= MIN_SDK_VOICE_COMMANDS
        val hasRecordAudioPermission = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val inCall = callDetector.isInCall()

        // voiceGateOpen() tem efeito colateral (corta o timer manual quando o movimento para) —
        // só chama quando as outras condições já deixam a escuta possível, senão o timer seria
        // cortado por engano com o motor desligado. Preserva o short-circuit que existia antes
        // de extrair a decisão pura pra VoiceGateDecision.
        val gateOpen = masterEnabled && sdkSupported && hasRecordAudioPermission && !inCall && voiceGateOpen()

        // gateOpen já embute as demais condições (só chega a true se todas já eram true), então
        // esta chamada só está re-conferindo o resultado através da mesma regra pura que os
        // testes exercitam — não custa nada e evita duas fontes de verdade pra decisão.
        val shouldListen = VoiceGateDecision.shouldListen(
            masterEnabled = masterEnabled,
            sdkSupported = sdkSupported,
            hasRecordAudioPermission = hasRecordAudioPermission,
            inCall = inCall,
            gateOpen = gateOpen
        )

        if (shouldListen && !voiceListening) startVoiceListening()
        else if (!shouldListen && voiceListening) stopVoiceListening()

        // Pedido do botão de microfone: dispensa a palavra de ativação para a próxima fala.
        // Fica DEPOIS do start, para o motor já existir.
        //
        // O pedido NÃO é zerado aqui: é ele que mantém o gate aberto (ver voiceGateOpen) pelos
        // segundos em que a pessoa leva o telefone à boca. Zerá-lo desligaria o microfone no
        // mesmo instante em que o botão acabou de ligá-lo, quando não há movimento nem timer
        // manual. Quem impede o rearme a cada tique é `directCommandArmedFor`, e a janela
        // fecha sozinha ao expirar.
        val directUntil = Prefs.directCommandUntil(applicationContext)
        if (voiceListening && directUntil > System.currentTimeMillis() && directUntil != directCommandArmedFor) {
            directCommandArmedFor = directUntil
            voiceEngine.armDirectCommand()
            android.util.Log.d(TAG_VOICE_GATE, "comando direto armado pelo botão de microfone")
        }
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
        // Botão de microfone: pedido explícito e curto, não depende de movimento nem do timer.
        val directOpen = Prefs.directCommandUntil(applicationContext) > now

        return motionOpen || manualOpen || directOpen
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

    private data class NotificationImage(
        val uri: Uri,
        val mimeType: String?
    )
}
