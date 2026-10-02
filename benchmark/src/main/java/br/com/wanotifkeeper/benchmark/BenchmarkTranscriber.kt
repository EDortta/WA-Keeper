package br.com.wanotifkeeper.benchmark

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

object BenchmarkTranscriber {
    data class Result(val text: String, val elapsedMs: Long)

    fun transcribe(filesDir: File, input: File, model: String = "small", language: String = ""): Result {
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
        return try {
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
            Result(
                text = parts.joinToString(" ").trim(),
                elapsedMs = (System.nanoTime() - started) / 1_000_000L
            )
        } finally {
            recognizer.release()
        }
    }
}
