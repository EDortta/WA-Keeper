package br.com.wanotifkeeper

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.com.wanotifkeeper.databinding.ActivityTranscriptionHistoryBinding
import kotlinx.coroutines.launch
import java.util.Locale

class TranscriptionHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscriptionHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptionHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val runs = NotifDatabase.get(this@TranscriptionHistoryActivity)
                .transcriptionRuns()
                .all()

            binding.rows.removeAllViews()
            binding.tvSummary.text = if (runs.isEmpty()) {
                "Nenhuma transcrição registrada ainda."
            } else {
                runs.size.toString() + if (runs.size == 1) " transcrição registrada" else " transcrições registradas"
            }

            runs.forEach { run ->
                binding.rows.addView(historyRow(run))
            }
        }
    }

    private fun historyRow(run: TranscriptionRunEntity): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }

        val method = when (TranscriptionMethod.fromCode(run.method)) {
            TranscriptionMethod.BASE_INT8 -> "Base"
            TranscriptionMethod.SMALL_INT8 -> "Small"
        }

        row.addView(cell(method, 1.0f))
        row.addView(cell(formatDuration(run.audioDurationMs), 1.05f))
        row.addView(cell(formatDuration(run.elapsedMs), 1.05f))
        row.addView(cell(qualityLabel(run), 1.45f))

        return row
    }

    private fun cell(value: String, weight: Float): TextView =
        TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(getColor(R.color.wa_text_primary))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            setPadding(dp(4), 0, dp(4), 0)
        }

    private fun qualityLabel(run: TranscriptionRunEntity): String {
        if (run.status != "DONE") return "Falha"
        return when (run.rating) {
            "INCOMPREHENSIBLE" -> "Incompreensível"
            "ACCEPTABLE" -> "Aceitável"
            "GOOD" -> "Boa"
            "EXCELLENT" -> "Excelente"
            else -> "Não avaliada"
        }
    }

    private fun formatDuration(ms: Long): String =
        if (ms <= 0L) "—"
        else String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
