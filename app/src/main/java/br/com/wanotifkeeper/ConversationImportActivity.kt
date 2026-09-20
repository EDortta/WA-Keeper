package br.com.wanotifkeeper

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class ConversationImportActivity : AppCompatActivity() {
    private val db by lazy { NotifDatabase.get(this) }
    private val memory by lazy { MemoryRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val result = runCatching { importIntent(intent) }
            result.onSuccess {
                Toast.makeText(
                    this@ConversationImportActivity,
                    "Importação concluída: ${it.imported} novas, ${it.duplicates} duplicadas",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                Toast.makeText(
                    this@ConversationImportActivity,
                    "Não foi possível importar: ${it.message ?: "formato inválido"}",
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }

    private suspend fun importIntent(intent: Intent): ImportResult = withContext(Dispatchers.IO) {
        val uris = extractUris(intent)
        require(uris.isNotEmpty()) { "nenhum arquivo recebido" }

        var imported = 0
        var duplicates = 0

        for (uri in uris) {
            val displayName = displayName(uri) ?: "WhatsApp export"
            val conversation = deriveConversationName(displayName)
            val texts = readExportTexts(uri, displayName)
            val entityId = memory.ensureEntityForConversation(
                packageName = PACKAGE_IMPORTED,
                sender = conversation,
                kind = "PERSON"
            )

            for ((sourceName, exportText) in texts) {
                val parsed = WhatsAppExportParser.parse(exportText)
                for (msg in parsed) {
                    val fp = WhatsAppExportParser.fingerprint(
                        conversation = conversation,
                        author = msg.author,
                        timestamp = msg.timestamp,
                        text = msg.text
                    )
                    if (db.dao().hasFingerprint(fp)) {
                        duplicates++
                        continue
                    }
                    val id = db.dao().insertIgnore(
                        NotifEntity(
                            sender = conversation,
                            text = msg.text,
                            timestamp = msg.timestamp,
                            packageName = PACKAGE_IMPORTED,
                            sourceType = "WHATSAPP_EXPORT",
                            sourceRef = "$sourceName#L${msg.sourceLine}",
                            author = msg.author,
                            fingerprint = fp
                        )
                    )
                    if (id > 0L) imported++ else duplicates++
                }
            }

            // O vínculo acima garante que todo o histórico importado já pertença à
            // mesma entidade transversal. O usuário poderá depois unir este vínculo
            // com números/grupos reais via MemoryRepository.linkConversation().
            memory.linkConversation(entityId, PACKAGE_IMPORTED, conversation, "IMPORTED_HISTORY")
        }

        ImportResult(imported, duplicates)
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    private fun readExportTexts(uri: Uri, displayName: String): List<Pair<String, String>> {
        val input = contentResolver.openInputStream(uri)
            ?: error("arquivo inacessível: $displayName")

        if (displayName.endsWith(".zip", ignoreCase = true)) {
            input.use { raw ->
                ZipInputStream(raw).use { zip ->
                    val out = mutableListOf<Pair<String, String>>()
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".txt", ignoreCase = true)) {
                            val text = BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).readText()
                            out += entry.name to text
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    require(out.isNotEmpty()) { "ZIP sem histórico .txt" }
                    return out
                }
            }
        }

        input.use {
            val text = BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            return listOf(displayName to text)
        }
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun deriveConversationName(filename: String): String {
        return filename
            .substringBeforeLast('.')
            .replace(Regex("""(?i)^WhatsApp Chat with\\s+"""), "")
            .replace(Regex("""(?i)^Conversa do WhatsApp com\\s+"""), "")
            .replace(Regex("""(?i)^Chat de WhatsApp con\\s+"""), "")
            .trim()
            .ifBlank { "Histórico importado" }
    }

    data class ImportResult(val imported: Int, val duplicates: Int)

    companion object {
        const val PACKAGE_IMPORTED = "wa.keeper.import"
    }
}
