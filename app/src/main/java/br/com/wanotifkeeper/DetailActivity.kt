package br.com.wanotifkeeper

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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
            binding.btnPlayText.setOnClickListener { audio.speakText(item.text) }

            binding.btnSchedule.setOnClickListener {
                startActivity(
                    ScheduledMessagesActivity.intent(this@DetailActivity, item.packageName, item.sender)
                )
            }

            val file = item.imagePath?.let(::File)
            val bmp = file?.takeIf { it.exists() && it.length() > 0L }?.let(::decodeSampled)
            when {
                bmp != null -> {
                    binding.imgAttachment.setImageBitmap(bmp)
                    binding.imgAttachment.visibility = View.VISIBLE
                }
                looksLikeMedia(item.text) -> binding.tvNoImage.visibility = View.VISIBLE
            }

            val originalAudio = item.audioPath?.let(::File)?.takeIf { it.exists() }
            if (originalAudio != null) {
                binding.btnPlayAudio.visibility = View.VISIBLE
                binding.btnPlayAudio.setOnClickListener { audio.play(originalAudio.absolutePath) }
            }
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
