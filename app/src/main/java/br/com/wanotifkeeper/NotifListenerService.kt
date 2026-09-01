package br.com.wanotifkeeper

import android.app.Notification
import android.graphics.Bitmap
import android.net.Uri
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
    // TTS e player de áudio só são criados quando há algo a reproduzir — sem segurar recursos à toa.
    private var speaker: Speaker? = null
    private var audioPlayer: AudioPlayer? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        motion.start()
        scope.launch { runPurge() }
    }

    override fun onListenerDisconnected() {
        motion.stop()
        speaker?.shutdown()
        speaker = null
        audioPlayer?.shutdown()
        audioPlayer = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        motion.stop()
        speaker?.shutdown()
        speaker = null
        audioPlayer?.shutdown()
        audioPlayer = null
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
            speak(title, text.trim())
        }

        val picture = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }.getOrNull()

        val messageImage = extractMessagingStyleImage(sbn.notification)
        val isImage = picture != null || messageImage != null || MediaVault.looksLikeImageMessage(text)

        scope.launch {
            var imagePath = picture?.let { savePicture(it, sbn.postTime) }
            if (imagePath == null && messageImage != null) {
                imagePath = MediaVault.captureImageUri(
                    applicationContext,
                    messageImage.uri,
                    messageImage.mimeType,
                    sbn.postTime
                )
            }

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

            // Algumas versões do WhatsApp não expõem bitmap/URI na notificação.
            // Nesses casos tentamos copiar a imagem recém-baixada do diretório oficial de mídia.
            if (imagePath == null && isImage) {
                captureImage(rowId, sbn.packageName, sbn.postTime)
            }

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
     * MessagingStyle pode carregar uma URI da imagem, mesmo quando EXTRA_PICTURE não existe.
     */
    private fun extractMessagingStyleImage(notification: Notification): NotificationImage? = runCatching {
        @Suppress("DEPRECATION")
        val bundles = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            .asReversed()
            .firstOrNull { msg ->
                msg.dataUri != null && msg.dataMimeType?.startsWith("image/", ignoreCase = true) == true
            }
            ?.let { msg -> NotificationImage(msg.dataUri!!, msg.dataMimeType) }
    }.getOrNull()

    /**
     * A imagem pode aparecer alguns instantes após a notificação; tentamos algumas vezes.
     */
    private suspend fun captureImage(rowId: Long, pkg: String, postTime: Long) {
        for (wait in longArrayOf(300, 1200, 3000, 6000)) {
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
                player().play(path)
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
