package br.com.wanotifkeeper

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AsrBenchmarkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputName = intent.getStringExtra("input").orEmpty()
        val model = intent.getStringExtra("model").orEmpty().ifBlank { "tiny" }
        val language = intent.getStringExtra("language").orEmpty()

        lifecycleScope.launch {
            val outDir = File(filesDir, "asr-benchmark").apply { mkdirs() }
            val resultFile = File(outDir, "result.json")
            resultFile.delete()

            var elapsedMs = 0L
            val result = runCatching {
                require(model in setOf("tiny", "base", "small")) {
                    "modelo inválido: $model"
                }

                val input = File(outDir, inputName)
                require(input.isFile && input.length() > 0L) {
                    "áudio de benchmark não encontrado: $inputName"
                }

                val modelFiles = ensureModel(model)
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
                    AndroidAudioDecoder.forEachMono16kChunk(input) { audio ->
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
            val json = JSONObject()
                .put("model", model)
                .put("language", language.ifBlank { "auto" })
                .put("elapsedMs", elapsedMs)

            result.fold(
                onSuccess = { text ->
                    json.put("ok", true)
                    json.put("text", text)
                },
                onFailure = { error ->
                    json.put("ok", false)
                    json.put("error", error.message ?: error.javaClass.simpleName)
                }
            )

            withContext(Dispatchers.IO) {
                resultFile.writeText(json.toString(), Charsets.UTF_8)
            }
            finish()
        }
    }

    private data class Files3(val encoder: File, val decoder: File, val tokens: File)

    private suspend fun ensureModel(model: String): Files3 = withContext(Dispatchers.IO) {
        val dir = File(filesDir, "asr-benchmark/models/$model").apply { mkdirs() }
        val encoder = File(dir, "$model-encoder.int8.onnx")
        val decoder = File(dir, "$model-decoder.int8.onnx")
        val tokens = File(dir, "$model-tokens.txt")

        listOf(encoder, decoder, tokens).forEach { target ->
            if (!target.isFile || target.length() == 0L) {
                download(
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$model/resolve/main/${target.name}?download=true",
                    target
                )
            }
        }
        Files3(encoder, decoder, tokens)
    }

    private fun download(url: String, target: File) {
        val temp = File(target.parentFile, target.name + ".part")
        temp.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
        }

        try {
            check(connection.responseCode in 200..299) {
                "download de ${target.name}: HTTP ${connection.responseCode}"
            }
            FileOutputStream(temp).use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            }
            check(temp.length() > 0L) { "download vazio: ${target.name}" }
            check(temp.renameTo(target)) { "falha ao instalar ${target.name}" }
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }
}
