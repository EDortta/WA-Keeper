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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

class BenchmarkActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            textSize = 18f
            setPadding(32, 48, 32, 32)
            text = "WA-Keeper ASR Benchmark\nCarregando estado..."
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
            startStatusMonitor()
            return
        }

        val inputName = intent.getStringExtra("input").orEmpty()
        if (inputName.isBlank()) {
            startStatusMonitor()
            return
        }

        val model = intent.getStringExtra("model").orEmpty().ifBlank { "small" }
        val language = intent.getStringExtra("language").orEmpty()
        scope.launch { runSingle(inputName, model, language) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startStatusMonitor() {
        scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    renderStatus(File(filesDir, "benchmark/status.json"))
                }
                statusView.text = text
                delay(1_000L)
            }
        }
    }

    private fun renderStatus(file: File): String {
        if (!file.isFile) {
            return "WA-Keeper ASR Benchmark\n\nAguardando início do benchmark."
        }

        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val state = json.optString("state", "unknown")
            val source = json.optString("sourceFileName", "")
            val input = json.optString("inputFileName", "")
            val variant = json.optString("variant", "")
            val round = json.optInt("round", 0)
            val order = json.optInt("order", 0)
            val completed = json.optInt("completedRuns", 0)
            val total = json.optInt("totalRuns", 0)
            val duration = json.optDouble("audioDurationSeconds", Double.NaN)
            val elapsed = json.optDouble("elapsedSeconds", Double.NaN)
            val rtf = json.optDouble("currentRtf", Double.NaN)
            val temp = json.optDouble("batteryTempC", Double.NaN)
            val thermal = json.optInt("thermalStatus", -1)

            buildString {
                append("WA-Keeper ASR Benchmark\n\n")
                append("Estado: ").append(state.uppercase()).append('\n')

                if (source.isNotBlank()) {
                    append("Arquivo original: ").append(source).append('\n')
                }
                if (input.isNotBlank()) {
                    append("Arquivo processado: ").append(input).append('\n')
                }
                if (variant.isNotBlank()) {
                    append("Variante: ").append(variant).append('\n')
                }
                if (round > 0) {
                    append("Rodada: ").append(round)
                    if (order > 0) append(" | ordem ").append(order)
                    append('\n')
                }
                if (total > 0) {
                    append("Execuções: ").append(completed).append('/').append(total).append('\n')
                }
                if (!duration.isNaN()) {
                    append("Áudio: ")
                        .append(String.format(Locale.US, "%.1f s", duration))
                        .append('\n')
                }
                if (!elapsed.isNaN()) {
                    append("Tempo gasto: ")
                        .append(String.format(Locale.US, "%.1f s", elapsed))
                        .append('\n')
                }
                if (!rtf.isNaN()) {
                    append("RTF atual: ")
                        .append(String.format(Locale.US, "%.2fx", rtf))
                        .append('\n')
                }
                val modelLoadMs = json.optLong("modelLoadMs", 0L)
                if (modelLoadMs > 0L) {
                    append("Carga do modelo: ")
                        .append(String.format(Locale.US, "%.1f s", modelLoadMs / 1000.0))
                        .append('\n')
                }
                if (!temp.isNaN()) {
                    append("Bateria: ")
                        .append(String.format(Locale.US, "%.1f °C", temp))
                        .append('\n')
                }
                if (thermal >= 0) {
                    append("Thermal: ").append(thermal).append('\n')
                }

                val message = json.optString("message", "")
                if (message.isNotBlank()) {
                    append('\n').append(message)
                }
            }
        }.getOrElse {
            "WA-Keeper ASR Benchmark\n\nLendo estado..."
        }
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
                BenchmarkTranscriber.create(filesDir, model, language).use { it.transcribe(input) }
            }
        }

        val json = JSONObject().put("model", model)

        result.fold(
            onSuccess = {
                json.put("ok", true)
                json.put("elapsedMs", it.inferenceMs)
                json.put("text", it.text)
                statusView.text =
                    "Concluído\nModelo: " + model + "\nTempo: " + it.inferenceMs + " ms"
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
