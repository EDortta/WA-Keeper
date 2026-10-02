package br.com.wanotifkeeper.benchmark

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class BenchmarkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(32, 48, 32, 32)
            text = "WA-Keeper ASR Benchmark\nPronto."
        }
        setContentView(statusView)

        if (intent.getBooleanExtra("autonomous", false)) {
            val serviceIntent = Intent(this, BenchmarkRunnerService::class.java).apply {
                action = BenchmarkRunnerService.ACTION_START
                putExtra(
                    BenchmarkRunnerService.EXTRA_REPEATS,
                    intent.getIntExtra("repeats", 3)
                )
                putExtra(
                    BenchmarkRunnerService.EXTRA_COOLDOWN_SECONDS,
                    intent.getIntExtra("cooldownSeconds", 120)
                )
                putExtra(
                    BenchmarkRunnerService.EXTRA_MODEL,
                    intent.getStringExtra("model").orEmpty().ifBlank { "small" }
                )
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusView.text =
                "Benchmark autônomo iniciado.\nPode desconectar o celular.\n" +
                "O WA-Keeper continua independente."
            return
        }

        val inputName = intent.getStringExtra("input").orEmpty()
        if (inputName.isBlank()) return

        val model = intent.getStringExtra("model").orEmpty().ifBlank { "small" }
        val language = intent.getStringExtra("language").orEmpty()
        scope.launch { runSingle(inputName, model, language) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runSingle(inputName: String, model: String, language: String) {
        val outDir = File(filesDir, "benchmark").apply { mkdirs() }
        val resultFile = File(outDir, "result.json")
        resultFile.delete()
        statusView.text = "Preparando modelo " + model + "..."

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val input = File(outDir, inputName)
                require(input.isFile && input.length() > 0L) {
                    "áudio não encontrado: " + inputName
                }
                BenchmarkTranscriber.transcribe(filesDir, input, model, language)
            }
        }

        val json = JSONObject().put("model", model)

        result.fold(
            onSuccess = {
                json.put("ok", true)
                json.put("elapsedMs", it.elapsedMs)
                json.put("text", it.text)
                statusView.text =
                    "Concluído\nModelo: " + model + "\nTempo: " + it.elapsedMs + " ms"
            },
            onFailure = {
                json.put("ok", false)
                json.put("elapsedMs", 0)
                json.put("error", it.message ?: it.javaClass.simpleName)
                statusView.text = "Falhou\n" + (it.message ?: it.javaClass.simpleName)
            }
        )

        withContext(Dispatchers.IO) {
            resultFile.writeText(json.toString(), Charsets.UTF_8)
        }
    }
}
