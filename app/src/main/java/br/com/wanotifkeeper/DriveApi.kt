package br.com.wanotifkeeper

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Cliente mínimo da Google Drive REST API.
 *
 * Usa somente o escopo drive.file: o WA Keeper enxerga e altera apenas os arquivos
 * que ele próprio cria. Nenhum acesso amplo ao Drive do usuário é solicitado.
 */
class DriveApi(private val context: Context) {
    private var token: String? = null

    fun ensureFolder(parentId: String, name: String): String {
        findChild(parentId, name, true)?.let { return it }

        val body = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", org.json.JSONArray().put(parentId))

        val response = request(
            method = "POST",
            url = "$API/files?fields=id",
            contentType = "application/json; charset=utf-8",
            body = body.toString().toByteArray(StandardCharsets.UTF_8)
        )
        return JSONObject(response).getString("id")
    }

    fun putText(parentId: String, name: String, mimeType: String, text: String): Boolean {
        val id = ensureFileMetadata(parentId, name, mimeType)
        uploadBytes(id, mimeType, text.toByteArray(StandardCharsets.UTF_8))
        return true
    }

    fun putFile(parentId: String, name: String, mimeType: String, source: File): Boolean {
        if (!source.exists() || source.length() <= 0L) return false
        val id = ensureFileMetadata(parentId, name, mimeType)
        requestStream(
            method = "PATCH",
            url = "$UPLOAD/files/$id?uploadType=media",
            contentType = mimeType,
            length = source.length()
        ) { connection ->
            source.inputStream().use { input ->
                connection.outputStream.use { output -> input.copyTo(output) }
            }
        }
        return true
    }

    private fun ensureFileMetadata(parentId: String, name: String, mimeType: String): String {
        findChild(parentId, name, false)?.let { return it }

        val body = JSONObject()
            .put("name", name)
            .put("mimeType", mimeType)
            .put("parents", org.json.JSONArray().put(parentId))

        val response = request(
            method = "POST",
            url = "$API/files?fields=id",
            contentType = "application/json; charset=utf-8",
            body = body.toString().toByteArray(StandardCharsets.UTF_8)
        )
        return JSONObject(response).getString("id")
    }

    private fun findChild(parentId: String, name: String, folder: Boolean): String? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val mimeClause = if (folder) " and mimeType = '$FOLDER_MIME'" else " and mimeType != '$FOLDER_MIME'"
        val query = "'$parentId' in parents and name = '$escaped' and trashed = false$mimeClause"
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val response = request(
            method = "GET",
            url = "$API/files?q=$encoded&spaces=drive&fields=files(id,name)&pageSize=1"
        )
        val files = JSONObject(response).getJSONArray("files")
        return if (files.length() == 0) null else files.getJSONObject(0).getString("id")
    }

    private fun uploadBytes(fileId: String, mimeType: String, bytes: ByteArray) {
        request(
            method = "PATCH",
            url = "$UPLOAD/files/$fileId?uploadType=media",
            contentType = mimeType,
            body = bytes
        )
    }

    private fun request(
        method: String,
        url: String,
        contentType: String? = null,
        body: ByteArray? = null
    ): String {
        return executeWithRefresh { authToken ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Accept", "application/json")
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) connection.outputStream.use { it.write(body) }
            readResponse(connection)
        }
    }

    private fun requestStream(
        method: String,
        url: String,
        contentType: String,
        length: Long,
        writer: (HttpURLConnection) -> Unit
    ): String {
        return executeWithRefresh { authToken ->
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("Authorization", "Bearer $authToken")
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Accept", "application/json")
                doOutput = true
                setFixedLengthStreamingMode(length)
            }
            writer(connection)
            readResponse(connection)
        }
    }

    private fun <T> executeWithRefresh(block: (String) -> T): T {
        val first = token ?: runBlocking { DriveAuth.accessToken(context) }.also { token = it }
        return try {
            block(first)
        } catch (e: DriveUnauthorizedException) {
            runBlocking { DriveAuth.invalidateToken(context, first) }
            val fresh = runBlocking { DriveAuth.accessToken(context) }.also { token = it }
            block(fresh)
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            connection.disconnect()
            throw DriveUnauthorizedException()
        }

        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("Google Drive HTTP $code: ${payload.take(500)}")
        }
        return payload
    }

    private class DriveUnauthorizedException : RuntimeException()

    companion object {
        const val ROOT = "root"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
