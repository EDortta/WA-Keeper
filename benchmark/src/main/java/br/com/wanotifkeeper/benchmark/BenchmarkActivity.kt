package br.com.wanotifkeeper.benchmark

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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

        val inputName = intent.getStringExtra("input").orEmpty()
        if (inputName.isBlank()) return

        val model = intent.getStringExtra("model").orEmpty().ifBlank { "small" }
        val language = intent.getStringExtra("language").orEmpty()
        scope.launch { runBenchmark(inputName, model, language) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runBenchmark(inputName: String, model: String, language: String) {
        val outDir = File(filesDir, "benchmark").apply { mkdirs() }
        val resultFile = File(outDir, "result.json")
        resultFile.delete()
        statusView.text = "Preparando modelo " + model + "..."

        var elapsedMs = 0L
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val input = File(outDir, inputName)
                require(input.isFile && input.length() > 0L) { "áudio não encontrado: " + inputName }

                val modelFiles = BenchmarkModelManager.ensureModel(filesDir, model)
                val started = System.nanoTime()

                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = modelFiles.encoder.absolutePath,
                            decoder = modelFiles.decoder.absolutePath,
                            language = language,
                            task = "transcribe"
                        ),
                        tokens = modelFiles.tokens.absolutePath,
                        numThreads = 4,
                        provider = "cpu",
                        modelType = "whisper"
                    )
                )

                val recognizer = OfflineRecognizer(config = config)
                try {
                    val parts = mutableListOf<String>()
                    AudioDecoder.forEachMono16kChunk(input) { audio ->
                        val stream = recognizer.createStream()
                        try {
                            stream.acceptWaveform(audio.samples, audio.sampleRate)
                            recognizer.decode(stream)
                            recognizer.getResult(stream).text.trim()
                                .takeIf { it.isNotBlank() }
                                ?.let(parts::add)
                        } finally {
                            stream.release()
                        }
                    }
                    parts.joinToString(" ").trim()
                } finally {
                    recognizer.release()
                    elapsedMs = (System.nanoTime() - started) / 1_000_000L
                }
            }
        }

        val json = JSONObject()
            .put("model", model)
            .put("language", language.ifBlank { "auto" })
            .put("elapsedMs", elapsedMs)

        result.fold(
            onSuccess = {
                json.put("ok", true)
                json.put("text", it)
                statusView.text = "Concluído\nModelo: " + model + "\nTempo: " + elapsedMs + " ms"
            },
            onFailure = {
                json.put("ok", false)
                json.put("error", it.message ?: it.javaClass.simpleName)
                statusView.text = "Falhou\n" + (it.message ?: it.javaClass.simpleName)
            }
        )

        withContext(Dispatchers.IO) {
            resultFile.writeText(json.toString(), Charsets.UTF_8)
        }
    }
}
