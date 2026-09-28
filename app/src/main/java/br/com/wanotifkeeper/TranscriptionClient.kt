package br.com.wanotifkeeper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class TranscriptionClient(private val context: Context) {

    suspend fun transcribe(file: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.exists() && file.length() > 0L) { "arquivo de áudio indisponível" }

            val boundary = "----WAKeeper${UUID.randomUUID()}"
            val connection = (URL(Prefs.transcriberUrl(context)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 180_000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                Prefs.transcriberToken(context).takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }

            BufferedOutputStream(connection.outputStream).use { out ->
                fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

                write("--$boundary\r\n")
                write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n"
                )
                write("Content-Type: audio/ogg\r\n\r\n")
                file.inputStream().use { input -> input.copyTo(out) }
                write("\r\n--$boundary--\r\n")
                out.flush()
            }

            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                error(
                    "transcritor respondeu HTTP $code" +
                        (body.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
                )
            }

            val text = JSONObject(body).optString("text").trim()
            if (text.isBlank()) error("transcritor não retornou texto")
            text
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
        val result = TranscriptionClient(context.applicationContext).transcribe(audio)
        result.fold(
            onSuccess = { text -> dao.setTranscript(notificationId, text) },
            onFailure = { error ->
                dao.setTranscriptError(notificationId, error.message ?: error.javaClass.simpleName)
            }
        )
        return result
    }
}
