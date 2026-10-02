package br.com.wanotifkeeper.benchmark

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ModelFiles(val encoder: File, val decoder: File, val tokens: File)

object BenchmarkModelManager {
    fun ensureModel(filesDir: File, model: String): ModelFiles {
        require(model in setOf("tiny", "base", "small")) { "modelo inválido: " + model }
        val dir = File(filesDir, "models/" + model).apply { mkdirs() }
        val encoder = File(dir, model + "-encoder.int8.onnx")
        val decoder = File(dir, model + "-decoder.int8.onnx")
        val tokens = File(dir, model + "-tokens.txt")

        listOf(encoder, decoder, tokens).forEach { target ->
            if (!target.isFile || target.length() == 0L) {
                val url = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-" +
                    model + "/resolve/main/" + target.name + "?download=true"
                download(url, target)
            }
        }
        return ModelFiles(encoder, decoder, tokens)
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
                "download de " + target.name + ": HTTP " + connection.responseCode
            }
            FileOutputStream(temp).use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            }
            check(temp.length() > 0L) { "download vazio: " + target.name }
            check(temp.renameTo(target)) { "falha ao instalar " + target.name }
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }
}
