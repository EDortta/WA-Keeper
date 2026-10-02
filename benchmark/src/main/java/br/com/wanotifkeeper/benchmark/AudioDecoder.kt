package br.com.wanotifkeeper.benchmark

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.floor
import kotlin.math.min

object AudioDecoder {
    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    fun forEachMono16kChunk(
        file: File,
        chunkSeconds: Int = 25,
        consumer: (Pcm) -> Unit
    ) {
        require(chunkSeconds in 5..30)
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

        val selectedFormat = inputFormat!!
        extractor.selectTrack(trackIndex)
        val mime = selectedFormat.getString(MediaFormat.KEY_MIME) ?: error("codec desconhecido")

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

        fun flush() {
            if (chunkSize <= 0) return
            val source = chunk.copyOf(chunkSize)
            val samples = if (outputSampleRate == 16_000) {
                source
            } else {
                resampleLinear(source, outputSampleRate, 16_000)
            }
            if (samples.isNotEmpty()) consumer(Pcm(samples, 16_000))
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
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, size, extractor.sampleTime, 0
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
                                    if (chunkSize >= chunk.size) flush()
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
                        flush()
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
            flush()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    private fun resampleLinear(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
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
}
