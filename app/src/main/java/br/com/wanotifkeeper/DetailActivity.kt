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

            val file = item.imagePath?.let(::File)
            val bmp = file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            when {
                bmp != null -> {
                    binding.imgAttachment.setImageBitmap(bmp)
                    binding.imgAttachment.visibility = View.VISIBLE
                }
                // O WhatsApp anuncia a imagem no texto mas nem sempre embute o bitmap.
                looksLikeMedia(item.text) -> binding.tvNoImage.visibility = View.VISIBLE
            }
        }
    }

    private fun looksLikeMedia(text: String) = Regex(
        "^(📷|🎥|🎤)|imagem|foto|imagen|photo|image|v[ií]deo|audio|áudio",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text.take(40))

    companion object {
        const val EXTRA_ID = "notif_id"
    }
}
