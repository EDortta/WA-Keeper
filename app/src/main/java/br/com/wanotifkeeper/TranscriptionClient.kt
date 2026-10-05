package br.com.wanotifkeeper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.min


enum class TranscriptionMethod(
    val code: String,
    val modelName: String,
    val label: String
) {
    BASE_INT8("BASE_INT8", "base", "Rápido · Whisper base INT8"),
    SMALL_INT8("SMALL_INT8", "small", "Qualidade · Whisper small INT8");

    companion object {
        fun fromCode(value: String?): TranscriptionMethod =
            entries.firstOrNull { it.code == value } ?: BASE_INT8
    }
}

object SherpaModelManager {

    data class ModelFiles(
        val encoder: File,
        val decoder: File,
        val tokens: File
    )

    private data class Spec(
        val name: String,
        val sha256: String?
    )

    private data class ModelSpec(
        val repo: String,
        val revision: String,
        val files: List<Spec>
    )

    private val modelSpecs = mapOf(
        TranscriptionMethod.BASE_INT8 to ModelSpec(
            repo = "csukuangfj/sherpa-onnx-whisper-base",
            revision = "bb53ee204431c90d314c1cc08d28d23e5b7927cc",
            files = listOf(
                Spec(
                    "base-encoder.int8.onnx",
                    "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"
                ),
                Spec(
                    "base-decoder.int8.onnx",
                    "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"
                ),
                Spec("base-tokens.txt", null)
            )
        ),
        TranscriptionMethod.SMALL_INT8 to ModelSpec(
            repo = "csukuangfj/sherpa-onnx-whisper-small",
            revision = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
            files = listOf(
                Spec(
                    "small-encoder.int8.onnx",
                    "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"
                ),
                Spec(
                    "small-decoder.int8.onnx",
                    "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"
                ),
                Spec("small-tokens.txt", null)
            )
        )
    )

    fun isInstalled(context: Context, method: TranscriptionMethod): Boolean {
        val spec = modelSpecs.getValue(method)
        val dir = modelDir(context, method)
        return spec.files.all { fileSpec ->
            val file = File(dir, fileSpec.name)
            file.isFile &&
                file.length() > 0L &&
                (fileSpec.sha256 == null || sha256(file) == fileSpec.sha256)
        }
    }

    suspend fun ensureInstalled(
        context: Context,
        method: TranscriptionMethod
    ): Result<ModelFiles> = withContext(Dispatchers.IO) {
        runCatching {
            val spec = modelSpecs.getValue(method)
            val dir = modelDir(context, method).apply { mkdirs() }

            spec.files.forEach { fileSpec ->
                val target = File(dir, fileSpec.name)
                val valid = target.isFile &&
                    target.length() > 0L &&
                    (fileSpec.sha256 == null || sha256(target) == fileSpec.sha256)

                if (valid) return@forEach

                target.delete()
                val tmp = File(dir, fileSpec.name + ".part")
                tmp.delete()
                download(urlFor(spec, fileSpec.name), tmp)

                fileSpec.sha256?.let { expected ->
                    val actual = sha256(tmp)
                    check(actual == expected) {
                        "checksum inválido de " + fileSpec.name +
                            ": esperado " + expected + ", recebido " + actual
                    }
                }

                check(tmp.renameTo(target)) {
                    "não foi possível concluir a instalação de " + fileSpec.name
                }
            }

            files(context, method)
        }
    }

    fun files(context: Context, method: TranscriptionMethod): ModelFiles {
        val prefix = method.modelName
        val dir = modelDir(context, method)
        return ModelFiles(
            encoder = File(dir, prefix + "-encoder.int8.onnx"),
            decoder = File(dir, prefix + "-decoder.int8.onnx"),
            tokens = File(dir, prefix + "-tokens.txt")
        )
    }

    private fun modelDir(context: Context, method: TranscriptionMethod): File =
        File(context.filesDir, "asr/sherpa-onnx-whisper-" + method.modelName)

    private fun urlFor(spec: ModelSpec, name: String): String =
        "https://huggingface.co/" + spec.repo + "/resolve/" + spec.revision +
            "/" + name + "?download=true"

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 300_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            val code = connection.responseCode
            check(code in 200..299) { "falha ao baixar modelo: HTTP " + code }

            FileOutputStream(target).use { output ->
                connection.inputStream.use { input ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
            check(target.length() > 0L) { "download do modelo ficou vazio" }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object AndroidAudioDecoder {

    data class Pcm(
        val samples: FloatArray,
        val sampleRate: Int
    )

    fun forEachMono16kChunk(
        file: File,
        chunkSeconds: Int = 25,
        consumer: (Pcm) -> Unit
    ) {
        require(chunkSeconds in 5..30) { "chunkSeconds deve ficar entre 5 e 30" }

        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                trackIndex = i
                inputFormat = format
                break
            }
        }
        check(trackIndex >= 0 && inputFormat != null) { "nenhuma faixa de áudio encontrada" }

        val selectedFormat = inputFormat
            ?: error("formato de áudio ausente")
        extractor.selectTrack(trackIndex)

        val mime = selectedFormat.getString(MediaFormat.KEY_MIME)
            ?: error("codec de áudio desconhecido")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(selectedFormat, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var outputSampleRate = selectedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = selectedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

        var chunk = FloatArray(maxOf(1, outputSampleRate * chunkSeconds))
        var chunkSize = 0

        fun flushChunk() {
            if (chunkSize <= 0) return
            val source = chunk.copyOf(chunkSize)
            val samples = if (outputSampleRate == TARGET_RATE) {
                source
            } else {
                resampleLinear(source, outputSampleRate, TARGET_RATE)
            }
            if (samples.isNotEmpty()) {
                consumer(Pcm(samples = samples, sampleRate = TARGET_RATE))
            }
            chunk = FloatArray(maxOf(1, outputSampleRate * chunkSeconds))
            chunkSize = 0
        }

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            val shorts = buffer.asShortBuffer()
                            val frame = ShortArray(shorts.remaining())
                            shorts.get(frame)

                            var i = 0
                            while (i < frame.size) {
                                var sum = 0f
                                var count = 0
                                for (ch in 0 until channels) {
                                    if (i + ch < frame.size) {
                                        sum += frame[i + ch] / 32768f
                                        count++
                                    }
                                }
                                if (count > 0) {
                                    if (chunkSize >= chunk.size) flushChunk()
                                    chunk[chunkSize++] = sum / count
                                }
                                i += channels
                            }
                        }

                        outputDone =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        flushChunk()
                        val format = codec.outputFormat
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        chunk = FloatArray(maxOf(1, outputSampleRate * chunkSeconds))
                        chunkSize = 0
                    }
                }
            }
            flushChunk()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    fun decodeToMono16k(file: File): Pcm {
        val chunks = mutableListOf<FloatArray>()
        var total = 0
        forEachMono16kChunk(file) { pcm ->
            chunks += pcm.samples
            total += pcm.samples.size
        }
        val all = FloatArray(total)
        var offset = 0
        chunks.forEach { part ->
            part.copyInto(all, offset)
            offset += part.size
        }
        return Pcm(samples = all, sampleRate = TARGET_RATE)
    }

    private fun resampleLinear(
        input: FloatArray,
        fromRate: Int,
        toRate: Int
    ): FloatArray {
        if (input.isEmpty() || fromRate <= 0 || fromRate == toRate) return input

        val ratio = fromRate.toDouble() / toRate.toDouble()
        val outputSize = maxOf(1, (input.size / ratio).toInt())
        val output = FloatArray(outputSize)

        for (i in output.indices) {
            val sourcePos = i * ratio
            val left = floor(sourcePos).toInt().coerceIn(0, input.lastIndex)
            val right = min(left + 1, input.lastIndex)
            val fraction = (sourcePos - left).toFloat()
            output[i] = input[left] * (1f - fraction) + input[right] * fraction
        }
        return output
    }

    private const val TARGET_RATE = 16_000
}


private object SherpaRecognizerPool {
    private val mutex = Mutex()
    private var activeMethod: TranscriptionMethod? = null
    private var recognizer: OfflineRecognizer? = null

    data class Result(
        val text: String,
        val inferenceMs: Long
    )

    suspend fun transcribe(
        context: Context,
        file: File,
        method: TranscriptionMethod
    ): Result = mutex.withLock {
        val current = ensureRecognizer(context, method)
        val started = System.nanoTime()
        val parts = mutableListOf<String>()

        AndroidAudioDecoder.forEachMono16kChunk(file) { audio ->
            val stream = current.createStream()
            try {
                stream.acceptWaveform(audio.samples, audio.sampleRate)
                current.decode(stream)
                current.getResult(stream).text.trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(parts::add)
            } finally {
                stream.release()
            }
        }

        val text = parts.joinToString(" ").trim()
        check(text.isNotBlank()) { "nenhuma fala detectada" }

        Result(
            text = text,
            inferenceMs = (System.nanoTime() - started) / 1_000_000L
        )
    }

    private suspend fun ensureRecognizer(
        context: Context,
        method: TranscriptionMethod
    ): OfflineRecognizer {
        if (activeMethod == method && recognizer != null) {
            return checkNotNull(recognizer)
        }

        recognizer?.release()
        recognizer = null
        activeMethod = null

        val model = SherpaModelManager.ensureInstalled(context, method).getOrThrow()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16_000,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = model.encoder.absolutePath,
                    decoder = model.decoder.absolutePath,
                    language = "",
                    task = "transcribe"
                ),
                tokens = model.tokens.absolutePath,
                numThreads = 4,
                provider = "cpu",
                modelType = "whisper"
            )
        )

        return OfflineRecognizer(config = config).also {
            recognizer = it
            activeMethod = method
        }
    }
}

class SherpaOfflineTranscriber(private val context: Context) {

    data class Outcome(
        val text: String,
        val inferenceMs: Long
    )

    suspend fun transcribe(
        file: File,
        method: TranscriptionMethod = TranscriptionMethod.BASE_INT8
    ): Result<Outcome> = withContext(Dispatchers.IO) {
        runCatching {
            val result = SherpaRecognizerPool.transcribe(
                context = context.applicationContext,
                file = file,
                method = method
            )
            Outcome(result.text, result.inferenceMs)
        }
    }
}

object AudioTranscriptionManager {

    suspend fun shouldAutoTranscribe(
        context: Context,
        packageName: String,
        sender: String
    ): Boolean {
        if (Prefs.isAutoTranscribeConversation(context, packageName, sender)) return true

        val entityIds = NotifDatabase.get(context).memory()
            .entityIdsForConversation(packageName, sender)

        return entityIds.any { Prefs.isAutoTranscribeEntity(context, it) }
    }

    suspend fun transcribe(
        context: Context,
        notificationId: Long,
        force: Boolean = false,
        method: TranscriptionMethod = TranscriptionMethod.BASE_INT8
    ): Result<String> {
        if (BankMode.isEnabled(context)) {
            return Result.failure(IllegalStateException("Modo Banco ativo"))
        }

        val db = NotifDatabase.get(context)
        val dao = db.dao()
        val item = dao.byId(notificationId)
            ?: return Result.failure(IllegalArgumentException("mensagem não encontrada"))

        if (!force && item.transcriptStatus == "DONE" && !item.transcript.isNullOrBlank()) {
            return Result.success(item.transcript)
        }
        if (!force && item.transcriptStatus == "RUNNING") {
            return Result.failure(IllegalStateException("transcrição já em andamento"))
        }

        val audio = item.audioPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            ?: return Result.failure(IllegalStateException("áudio não está disponível"))

        val startedAt = System.currentTimeMillis()
        val audioDurationMs = audioDurationMs(audio)
        dao.markTranscriptionRunning(notificationId)

        var inferenceMs = 0L
        val transcriberResult = SherpaOfflineTranscriber(context.applicationContext)
            .transcribe(audio, method)

        val result: Result<String> = transcriberResult.fold(
            onSuccess = { outcome ->
                inferenceMs = outcome.inferenceMs
                dao.setTranscript(notificationId, outcome.text)
                Result.success(outcome.text)
            },
            onFailure = { error ->
                dao.setTranscriptError(
                    notificationId,
                    error.message ?: error.javaClass.simpleName
                )
                Result.failure(error)
            }
        )

        val elapsedMs = System.currentTimeMillis() - startedAt
        db.transcriptionRuns().insert(
            TranscriptionRunEntity(
                notificationId = notificationId,
                method = method.code,
                startedAt = startedAt,
                elapsedMs = elapsedMs,
                inferenceMs = inferenceMs,
                audioDurationMs = audioDurationMs,
                transcriptChars = result.getOrNull()?.length ?: 0,
                status = if (result.isSuccess) "DONE" else "ERROR",
                error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            )
        )

        return result
    }

    suspend fun rateLatest(
        context: Context,
        notificationId: Long,
        rating: String
    ): Boolean {
        require(
            rating in setOf("INCOMPREHENSIBLE", "ACCEPTABLE", "GOOD", "EXCELLENT")
        ) { "avaliação inválida" }

        val dao = NotifDatabase.get(context).transcriptionRuns()
        val latest = dao.latestForNotification(notificationId) ?: return false
        if (latest.status != "DONE") return false
        dao.rate(latest.id, rating)
        return true
    }

    private fun audioDurationMs(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)
}
