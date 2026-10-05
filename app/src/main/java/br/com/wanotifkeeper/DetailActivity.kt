package br.com.wanotifkeeper

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import br.com.wanotifkeeper.databinding.ActivityDetailBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mostra a mensagem inteira — texto sem corte e imagem, quando a notificação trouxe uma. */
class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private val audio by lazy { AudioArbiter.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) { finish(); return }

        lifecycleScope.launch {
            val item = NotifDatabase.get(this@DetailActivity).dao().byId(id)
            if (item == null) { finish(); return@launch }

            binding.tvSender.text = item.sender
            binding.tvTime.text = fmt.format(Date(item.timestamp))
            binding.tvText.text = item.text

            val originalAudio = item.audioPath?.let(::File)?.takeIf { it.exists() }
            val isVoiceMessage = originalAudio != null || MediaHints.looksLikeVoiceMessage(item.text)
            binding.btnPlayText.visibility = if (isVoiceMessage) View.GONE else View.VISIBLE
            if (!isVoiceMessage) {
                binding.btnPlayText.setOnClickListener { audio.speakText(item.text) }
            }

            binding.btnSchedule.setOnClickListener {
                startActivity(
                    ScheduledMessagesActivity.intent(this@DetailActivity, item.packageName, item.sender)
                )
            }

            val file = item.imagePath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            val bmp = file?.let(::decodeSampled)
            when {
                bmp != null -> {
                    binding.imgAttachment.setImageBitmap(bmp)
                    binding.imgAttachment.visibility = View.VISIBLE
                }
                looksLikeMedia(item.text) -> binding.tvNoImage.visibility = View.VISIBLE
            }

            val shareableFile = file ?: originalAudio
            binding.btnShareAttachment.visibility = if (shareableFile != null) View.VISIBLE else View.GONE
            binding.btnShareAttachment.setOnClickListener {
                shareableFile?.let(::shareFile)
            }

            if (originalAudio != null) {
                binding.audioControls.visibility = View.VISIBLE
                binding.btnTranscribeAudio.visibility = View.VISIBLE

                binding.btnPlayAudio.setOnClickListener {
                    if (!audio.resumeFile()) {
                        audio.play(originalAudio.absolutePath)
                    }
                }
                binding.btnPauseAudio.setOnClickListener {
                    audio.pauseFile()
                }
                binding.btnStopAudio.setOnClickListener {
                    audio.stopFile()
                }

                renderTranscript(item)
                renderFeedback(item.id)
                binding.btnTranscribeAudio.setOnClickListener {
                    chooseTranscriptionMethod(item.id)
                }

                binding.btnTranscriptIncomprehensible.setOnClickListener {
                    rateTranscript(item.id, "INCOMPREHENSIBLE")
                }
                binding.btnTranscriptAcceptable.setOnClickListener {
                    rateTranscript(item.id, "ACCEPTABLE")
                }
                binding.btnTranscriptGood.setOnClickListener {
                    rateTranscript(item.id, "GOOD")
                }
                binding.btnTranscriptExcellent.setOnClickListener {
                    rateTranscript(item.id, "EXCELLENT")
                }
            }
        }
    }

    private fun renderTranscript(item: NotifEntity) {
        when {
            !item.transcript.isNullOrBlank() -> {
                binding.tvTranscript.visibility = View.VISIBLE
                binding.tvTranscript.text = item.transcript
                binding.btnTranscribeAudio.text = "Transcrever novamente"
            }
            item.transcriptStatus == "ERROR" -> {
                binding.tvTranscript.visibility = View.VISIBLE
                binding.tvTranscript.text = "Falha na transcrição: ${item.transcriptError ?: "sem detalhe"}"
                binding.btnTranscribeAudio.text = "Tentar novamente"
            }
            else -> {
                binding.tvTranscript.visibility = View.GONE
                binding.btnTranscribeAudio.text = "Transcrever áudio"
            }
        }
    }

    private fun chooseTranscriptionMethod(id: Long) {
        val methods = arrayOf(
            TranscriptionMethod.BASE_INT8,
            TranscriptionMethod.SMALL_INT8
        )
        AlertDialog.Builder(this)
            .setTitle("Método de transcrição")
            .setItems(methods.map { it.label }.toTypedArray()) { _, which ->
                transcribe(id, methods[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun transcribe(id: Long, method: TranscriptionMethod) {
        binding.progressTranscription.visibility = View.VISIBLE
        binding.btnTranscribeAudio.isEnabled = false
        lifecycleScope.launch {
            val result = AudioTranscriptionManager.transcribe(
                context = applicationContext,
                notificationId = id,
                force = true,
                method = method
            )
            binding.progressTranscription.visibility = View.GONE
            binding.btnTranscribeAudio.isEnabled = true

            val refreshed = NotifDatabase.get(this@DetailActivity).dao().byId(id)
            if (refreshed != null) renderTranscript(refreshed)
            renderFeedback(id)

            result.exceptionOrNull()?.let { error ->
                if (refreshed?.transcript.isNullOrBlank()) {
                    binding.tvTranscript.visibility = View.VISIBLE
                    binding.tvTranscript.text =
                        "Falha na transcrição: " + (error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private fun renderFeedback(notificationId: Long) {
        lifecycleScope.launch {
            val run = NotifDatabase.get(this@DetailActivity)
                .transcriptionRuns()
                .latestForNotification(notificationId)

            val visible = run?.status == "DONE"
            binding.tvTranscriptMethod.visibility = if (visible) View.VISIBLE else View.GONE
            binding.tvTranscriptFeedbackTitle.visibility = if (visible) View.VISIBLE else View.GONE
            binding.transcriptFeedbackRow1.visibility = if (visible) View.VISIBLE else View.GONE
            binding.transcriptFeedbackRow2.visibility = if (visible) View.VISIBLE else View.GONE

            if (!visible || run == null) return@launch

            val method = TranscriptionMethod.fromCode(run.method)
            val seconds = run.elapsedMs / 1000.0
            val rtf = if (run.audioDurationMs > 0L) {
                run.inferenceMs.toDouble() / run.audioDurationMs.toDouble()
            } else null

            binding.tvTranscriptMethod.text = buildString {
                append(method.label)
                append(" · ")
                append(String.format(Locale.getDefault(), "%.1f s", seconds))
                if (rtf != null) {
                    append(" · RTF ")
                    append(String.format(Locale.getDefault(), "%.2fx", rtf))
                }
            }

            markSelectedRating(run.rating)
        }
    }

    private fun rateTranscript(notificationId: Long, rating: String) {
        lifecycleScope.launch {
            val saved = AudioTranscriptionManager.rateLatest(
                context = applicationContext,
                notificationId = notificationId,
                rating = rating
            )
            if (saved) {
                markSelectedRating(rating)
                Toast.makeText(
                    this@DetailActivity,
                    "Avaliação registrada.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun markSelectedRating(rating: String?) {
        val buttons = listOf(
            binding.btnTranscriptIncomprehensible to "INCOMPREHENSIBLE",
            binding.btnTranscriptAcceptable to "ACCEPTABLE",
            binding.btnTranscriptGood to "GOOD",
            binding.btnTranscriptExcellent to "EXCELLENT"
        )

        buttons.forEach { (button, value) ->
            val selected = rating == value
            button.alpha = if (selected) 1f else 0.72f
            button.strokeWidth = if (selected) 3 else 1
        }
    }

    private fun shareFile(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.files",
                file
            )
            val mime = mimeTypeFor(file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Compartilhar arquivo"))
        }.onFailure {
            Toast.makeText(this, "Não foi possível compartilhar este arquivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeTypeFor(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "opus" -> "audio/ogg"
                else -> "application/octet-stream"
            }
    }

    private fun decodeSampled(file: File): android.graphics.Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_IMAGE_PX || bounds.outHeight / sample > MAX_IMAGE_PX) {
            sample *= 2
        }
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun looksLikeMedia(text: String) =
        MediaHints.looksLikeImageMessage(text, isGroup = true) ||
            MediaHints.looksLikeVoiceMessage(text)

    companion object {
        const val EXTRA_ID = "notif_id"
        private const val MAX_IMAGE_PX = 2048
    }
}
