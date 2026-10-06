package br.com.wanotifkeeper

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DriveBackupStore {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US)

    suspend fun backupMessage(context: Context, rowId: Long): Boolean = withContext(Dispatchers.IO) {
        val dao = NotifDatabase.get(context).dao()
        val item = dao.byId(rowId) ?: return@withContext false
        if (!DriveBackupPolicy.effectiveEnabled(context, item.packageName, item.sender)) {
            return@withContext false
        }
        backupItem(context, item)
    }

    suspend fun backupConversation(
        context: Context,
        packageName: String,
        sender: String
    ): Int = withContext(Dispatchers.IO) {
        val all = NotifDatabase.get(context).dao().getAll()
        var count = 0
        for (item in all) {
            if (item.packageName == packageName && item.sender.equals(sender, ignoreCase = true)) {
                if (backupItem(context, item)) count++
            }
        }
        count
    }

    suspend fun backupEntity(context: Context, entityId: Long): Int = withContext(Dispatchers.IO) {
        val db = NotifDatabase.get(context)
        var count = 0
        for (link in db.memory().linksForEntity(entityId)) {
            count += backupConversation(context, link.packageName, link.sender)
        }
        count
    }

    suspend fun backupAllEnabled(context: Context): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (item in NotifDatabase.get(context).dao().getAll()) {
            if (DriveBackupPolicy.effectiveEnabled(context, item.packageName, item.sender) &&
                backupItem(context, item)
            ) count++
        }
        count
    }

    private fun backupItem(context: Context, item: NotifEntity): Boolean {
        val rootUri = DriveBackupPolicy.rootUri(context) ?: return false
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return false
        if (!root.canWrite()) return false

        val appFolder = childDirectory(root, "WA-Keeper") ?: return false
        val accountFolder = childDirectory(appFolder, accountName(item.packageName)) ?: return false
        val conversationFolder = childDirectory(accountFolder, safeName(item.sender)) ?: return false
        val messagesFolder = childDirectory(conversationFolder, "messages") ?: return false
        val mediaFolder = childDirectory(conversationFolder, "media") ?: return false

        val baseName = stamp.format(Date(item.timestamp)) + "-" + item.id
        val imageName = item.imagePath?.let { copyMedia(context, mediaFolder, File(it), baseName + "-image") }
        val audioName = item.audioPath?.let { copyMedia(context, mediaFolder, File(it), baseName + "-audio") }

        val json = JSONObject()
            .put("id", item.id)
            .put("sender", item.sender)
            .put("text", item.text)
            .put("timestamp", item.timestamp)
            .put("packageName", item.packageName)
            .put("sourceType", item.sourceType)
            .put("sourceRef", item.sourceRef ?: JSONObject.NULL)
            .put("author", item.author ?: JSONObject.NULL)
            .put("conversationKey", item.conversationKey ?: JSONObject.NULL)
            .put("transcript", item.transcript ?: JSONObject.NULL)
            .put("image", imageName ?: JSONObject.NULL)
            .put("audio", audioName ?: JSONObject.NULL)

        val targetName = "$baseName.json"
        val target = messagesFolder.findFile(targetName)
            ?: messagesFolder.createFile("application/json", targetName)
            ?: return false

        context.contentResolver.openOutputStream(target.uri, "wt")?.bufferedWriter().use { writer ->
            if (writer == null) return false
            writer.write(json.toString(2))
        }
        return true
    }

    private fun copyMedia(
        context: Context,
        folder: DocumentFile,
        source: File,
        baseName: String
    ): String? {
        if (!source.exists() || source.length() <= 0L) return null
        val ext = source.extension.takeIf { it.isNotBlank() }?.lowercase() ?: "bin"
        val targetName = "$baseName.$ext"
        val target = folder.findFile(targetName)
            ?: folder.createFile(mimeFor(ext), targetName)
            ?: return null
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: return null
        return targetName
    }

    private fun childDirectory(parent: DocumentFile, name: String): DocumentFile? =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)

    private fun accountName(packageName: String): String = when (packageName) {
        Prefs.PKG_BUSINESS -> "WhatsApp Business"
        Prefs.PKG_WHATSAPP -> "WhatsApp"
        else -> safeName(packageName)
    }

    private fun safeName(value: String): String {
        val clean = value.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
        return clean.ifBlank { "conversa" }
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}
