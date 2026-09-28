package br.com.wanotifkeeper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.min

object SherpaModelManager {

    data class ModelFiles(
        val encoder: File,
        val decoder: File,
        val tokens: File
    )

    private const val MODEL_DIR = "sherpa-onnx-whisper-tiny"
    private const val PINNED_REVISION = "65176e2deb88badc814a94058666cadccc29b61c"

    private data class Spec(
        val name: String,
        val sha256: String
    )

    private val specs = listOf(
        Spec(
            "tiny-encoder.int8.onnx",
            "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"
        ),
        Spec(
            "tiny-decoder.int8.onnx",
            "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"
        ),
        Spec(
            "tiny-tokens.txt",
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
        )
    )

    fun isInstalled(context: Context): Boolean {
        val dir = modelDir(context)
        return specs.all { spec ->
            val file = File(dir, spec.name)
            file.isFile && file.length() > 0L && sha256(file) == spec.sha256
        }
    }

    suspend fun ensureInstalled(context: Context): Result<ModelFiles> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = modelDir(context).apply { mkdirs() }

            specs.forEach { spec ->
                val target = File(dir, spec.name)
                if (target.isFile && target.length() > 0L && sha256(target) == spec.sha256) {
                    return@forEach
                }

                target.delete()
                val tmp = File(dir, spec.name + ".part")
                tmp.delete()
                download(urlFor(spec.name), tmp)

                val actual = sha256(tmp)
                check(actual == spec.sha256) {
                    "checksum inválido de ${spec.name}: esperado ${spec.sha256}, recebido $actual"
                }

                check(tmp.renameTo(target)) {
                    "não foi possível concluir a instalação de ${spec.name}"
                }
            }

            files(context)
        }
    }

    fun files(context: Context): ModelFiles {
        val dir = modelDir(context)
        return ModelFiles(
            encoder = File(dir, "tiny-encoder.int8.onnx"),
            decoder = File(dir, "tiny-decoder.int8.onnx"),
            tokens = File(dir, "tiny-tokens.txt")
        )
    }

    private fun modelDir(context: Context): File =
        File(context.filesDir, "asr/$MODEL_DIR")

    private fun urlFor(name: String): String =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/$PINNED_REVISION/$name?download=true"

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            val code = connection.responseCode
            check(code in 200..299) { "falha ao baixar modelo: HTTP $code" }

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

    fun decodeToMono16k(file: File): Pcm {
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

        extractor.selectTrack(trackIndex)
        val mime = inputFormat!!.getString(MediaFormat.KEY_MIME)
            ?: error("codec de áudio desconhecido")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val pcm = ArrayList<Float>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var outputSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

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
                                if (count > 0) pcm += sum / count
                                i += channels
                            }
                        }

                        outputDone =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        val source = pcm.toFloatArray()
        if (outputSampleRate == TARGET_RATE) return Pcm(source, TARGET_RATE)

        return Pcm(
            samples = resampleLinear(source, outputSampleRate, TARGET_RATE),
            sampleRate = TARGET_RATE
        )
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

class SherpaOfflineTranscriber(private val context: Context) {

    suspend fun transcribe(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val model = SherpaModelManager.ensureInstalled(context).getOrThrow()
            val audio = AndroidAudioDecoder.decodeToMono16k(file)

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
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "whisper"
                )
            )

            val recognizer = OfflineRecognizer(config = config)
            try {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(audio.samples, audio.sampleRate)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream).text.trim()
                    check(result.isNotBlank()) { "nenhuma fala detectada" }
                    result
                } finally {
                    stream.release()
                }
            } finally {
                recognizer.release()
            }
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
        force: Boolean = false
    ): Result<String> {
        if (BankMode.isEnabled(context)) {
            return Result.failure(IllegalStateException("Modo Banco ativo"))
        }

        val dao = NotifDatabase.get(context).dao()
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

        dao.markTranscriptionRunning(notificationId)
        val result = SherpaOfflineTranscriber(context.applicationContext).transcribe(audio)
        result.fold(
            onSuccess = { text -> dao.setTranscript(notificationId, text) },
            onFailure = { error ->
                dao.setTranscriptError(
                    notificationId,
                    error.message ?: error.javaClass.simpleName
                )
            }
        )
        return result
    }
}
