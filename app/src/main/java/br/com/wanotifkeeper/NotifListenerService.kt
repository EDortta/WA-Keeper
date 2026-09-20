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
        "com.whatsapp.w4b"
    )

    @Volatile private var lastPurge = 0L

    private val motion by lazy { MotionDetector(applicationContext) }
    private val callDetector by lazy {
        CallDetector(applicationContext) {
            flushPendingAfterCall()
            updateListeningState()
        }
    }
    // TTS e player agora são fachadas para o mesmo AudioArbiter; continuam lazy para manter
    // compatibilidade com os chamadores existentes sem manter objetos locais desnecessários.
    private var speaker: Speaker? = null
    private var audioPlayer: AudioPlayer? = null
    private var beeper: Beeper? = null

    @Volatile private var voiceListening = false
    @Volatile private var directCommandArmedFor = 0L

    private val voicePrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_VOICE_COMMANDS_ENABLED || key == Prefs.KEY_DIRECT_COMMAND_UNTIL) {
                android.util.Log.d(TAG_VOICE_GATE, "$key mudou — reavaliando na hora")
                scope.launch { updateListeningState() }
            }
        }

    private val voiceEngine: VoiceCommandEngine by lazy {
        VoiceCommandEngine(
            context = applicationContext,
            scope = scope,
            dao = NotifDatabase.get(applicationContext).dao(),
            say = { text -> sayPrompt(text) },
            announce = { sender, text -> speak(sender, text) },
            // Speaker delega ao arbiter global, portanto isto também enxerga áudio de arquivo.
            isSpeakerBusy = { speaker?.isBusy() ?: AudioArbiter.get(applicationContext).isBusy() },
            defaultAccountPkg = { Prefs.voiceDefaultAccountPkg(applicationContext) },
            onSpeechPackMissing = {
                Prefs.setSpeechPackMissing(applicationContext, true)
                android.util.Log.d(TAG_VOICE_GATE, "pacote de voz pt-BR indisponível — comandos desligados nesta sessão")
            },
            onRecognitionWorking = {
                if (Prefs.isSpeechPackMissing(applicationContext)) {
                    Prefs.setSpeechPackMissing(applicationContext, false)
                }
            },
            onDirectSessionEnded = {
                directCommandArmedFor = 0L
                Prefs.setDirectCommandUntil(applicationContext, 0L)
                scope.launch {
                    updateListeningState()
                    if (voiceListening) voiceEngine.resumeAfterDirect()
                }
            }
        )
    }

    private val pendingDuringCall = mutableListOf<PendingPlayback>()

    private sealed class PendingPlayback {
        data class Text(val sender: String, val text: String) : PendingPlayback()
        data class Audio(val path: String) : PendingPlayback()
    }

    private val repostGuard = RepostGuard()
    private val announcementGuard = AnnouncementGuard()

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

        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = sbn.notification.extras ?: return
        val rawTitle = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return

        val conversationTitle =
            ConversationIdentity.messagingConversationTitle(sbn.notification)
                ?: extras
                    .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

        val title = ConversationIdentity.canonicalSender(
            conversationTitle ?: rawTitle,
            sbn.packageName
        )
        val conversationKey = ConversationIdentity.stableKey(sbn, title)

        if (NoiseFilter.isNoise(title, text)) return

        cacheReplyAction(sbn, title)

        val picture = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }.getOrNull()

        val messageImage = extractMessagingStyleImage(sbn.notification, sbn.postTime)
        val isGroup = extras.getBoolean("android.isGroupConversation", false)
        val isImage = picture != null || messageImage != null ||
            MediaVault.looksLikeImageMessage(text, isGroup)
        val isVoice = MediaVault.looksLikeVoiceMessage(text)

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

        // O modo manual permite explicitamente a leitura automática mesmo parado; a preferência
        // por conta continua sendo respeitada. O botão PLAY individual não passa por esta regra.
        val playbackAllowed = motion.isInMotion() || ManualReadMode.isEnabled()
        if (!isVoice && Prefs.isTtsEnabled(applicationContext, sbn.packageName) && playbackAllowed) {
            val announce = announcementGuard.shouldAnnounce(
                packageName = sbn.packageName,
                notificationKey = sbn.key,
                sender = title,
                text = text.trim(),
                now = System.currentTimeMillis()
            )
            if (announce) {
                if (callDetector.isInCall()) {
                    synchronized(pendingDuringCall) { pendingDuringCall.add(PendingPlayback.Text(title, text.trim())) }
                    beeper().beep()
                } else {
                    speak(title, text.trim())
                }
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
                    imagePath = imagePath,
                    conversationKey = conversationKey
                )
            )

            // Se o usuário já associou este contato do telefone a uma entidade,
            // conecta automaticamente a primeira conversa recebida desse contato.
            runCatching {
                MemoryRepository(applicationContext)
                    .maybeLinkIncomingConversation(sbn.packageName, title)
            }

            record.rowId.complete(rowId)

            val imageJob = if (imagePath == null && isImage) {
                scope.launch { captureImage(rowId, sbn.packageName, sbn.postTime) }
            } else null

            runCatching { ScheduledMessageTrigger.onIncoming(applicationContext, sbn, title) }

            if (isVoice &&
                Prefs.isAudioCaptureEnabled(applicationContext, sbn.packageName) &&
                !Prefs.isAudioBlocked(applicationContext, title)
            ) {
                captureAudio(rowId, sbn.packageName, sbn.postTime)
            }
            imageJob?.join()
            runPurge()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in watchedPackages) ReplyActionRegistry.forget(sbn.key)
    }

    private fun cacheReplyAction(sbn: StatusBarNotification, sender: String) {
        val resultKey = ReplyActionRegistry.remember(
            packageName = sbn.packageName,
            sender = sender,
            notificationKey = sbn.key,
            actions = sbn.notification.actions
        ) ?: return
        android.util.Log.d(TAG, "Reply action cached for ${sbn.packageName}|$sender (key=$resultKey)")
    }

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
        val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            .lastOrNull() ?: return null
        val uri = last.dataUri ?: return null
        if (last.dataMimeType?.startsWith("image/", ignoreCase = true) != true) return null
        if (last.timestamp > 0L && kotlin.math.abs(postTime - last.timestamp) > MESSAGE_TS_TOLERANCE_MS) {
            return null
        }
        NotificationImage(uri, last.dataMimeType)
    }.getOrNull()

    private suspend fun captureImage(rowId: Long, pkg: String, postTime: Long) {
        for (wait in MediaVault.IMAGE_RETRY_DELAYS_MS) {
            delay(wait)
            val path = MediaVault.captureLatestImage(applicationContext, pkg, postTime) ?: continue
            NotifDatabase.get(applicationContext).dao().setImagePath(rowId, path)
            return
        }
    }

    private suspend fun captureAudio(rowId: Long, pkg: String, postTime: Long) {
        for (wait in longArrayOf(300, 1200, 3000, 6000)) {
            delay(wait)
            val path = MediaVault.captureLatest(applicationContext, pkg, postTime) ?: continue
            NotifDatabase.get(applicationContext).dao().setAudioPath(rowId, path)
            if (Prefs.isAudioPlayInMotion(applicationContext) &&
                (motion.isInMotion() || ManualReadMode.isEnabled())
            ) {
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
        audioPlayer ?: AudioPlayer(applicationContext).also { audioPlayer = it }

    private fun speak(sender: String, text: String) {
        val s = speaker ?: Speaker(applicationContext).also { speaker = it }
        s.announce(sender, text)
    }

    private fun sayPrompt(text: String) {
        val s = speaker ?: Speaker(applicationContext).also { speaker = it }
        s.say(text)
    }

    private fun beeper(): Beeper =
        beeper ?: Beeper().also { beeper = it }

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

    private suspend fun runVoiceGateLoop() {
        while (scope.isActive) {
            updateListeningState()
            delay(VOICE_GATE_CHECK_MS)
        }
    }

    private fun updateListeningState() {
        val masterEnabled = Prefs.isVoiceCommandsEnabled(applicationContext)
        val sdkSupported = Build.VERSION.SDK_INT >= MIN_SDK_VOICE_COMMANDS
        val hasRecordAudioPermission = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val inCall = callDetector.isInCall()

        val gateOpen = masterEnabled && sdkSupported && hasRecordAudioPermission && !inCall && voiceGateOpen()

        val shouldListen = VoiceGateDecision.shouldListen(
            masterEnabled = masterEnabled,
            sdkSupported = sdkSupported,
            hasRecordAudioPermission = hasRecordAudioPermission,
            inCall = inCall,
            gateOpen = gateOpen
        )

        val startedNow = shouldListen && !voiceListening
        if (startedNow) startVoiceListening()
        else if (!shouldListen && voiceListening) stopVoiceListening()

        val directUntil = Prefs.directCommandUntil(applicationContext)
        if (voiceListening && directUntil > System.currentTimeMillis() && directUntil != directCommandArmedFor) {
            directCommandArmedFor = directUntil
            voiceEngine.armDirectCommand(restart = !startedNow)
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
                Prefs.setManualListenUntil(applicationContext, 0L)
                manualOpen = false
            }
        }
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

    private fun savePicture(bmp: Bitmap, postTime: Long): String? = runCatching {
        val file = File(Retention.imageDir(applicationContext), "img-$postTime-${bmp.hashCode()}.jpg")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.absolutePath
    }.getOrNull()

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
