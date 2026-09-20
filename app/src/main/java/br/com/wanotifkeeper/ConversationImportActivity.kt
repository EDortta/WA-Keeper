package br.com.wanotifkeeper

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import br.com.wanotifkeeper.databinding.ActivityConversationImportBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class ConversationImportActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConversationImportBinding
    private val db by lazy { NotifDatabase.get(this) }
    private val memory by lazy { MemoryRepository(this) }

    private var payload: ImportPayload? = null
    private var selectedEntityId: Long? = null
    private lateinit var entityAdapter: ImportEntityAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnCreateEntity.setOnClickListener { createEntityDialog() }
        binding.btnImport.setOnClickListener { importSelected() }

        entityAdapter = ImportEntityAdapter(
            selectedId = { selectedEntityId },
            onSelect = { id ->
                selectedEntityId = id
                entityAdapter.notifyDataSetChanged()
                binding.btnImport.isEnabled = true
            }
        )
        binding.recyclerImportEntities.layoutManager = LinearLayoutManager(this)
        binding.recyclerImportEntities.adapter = entityAdapter

        lifecycleScope.launch {
            binding.progressImport.visibility = View.VISIBLE
            binding.importContent.visibility = View.GONE

            val prepared = runCatching { prepareIntent(intent) }
            prepared.onSuccess { loaded ->
                payload = loaded
                binding.tvImportConversation.text = loaded.conversation
                binding.tvImportSummary.text =
                    "${loaded.messages.size} mensagens reconhecidas · ${loaded.sourceLabel}"

                val entities = db.memory().entitiesFlow().first()
                entityAdapter.submit(entities)
                binding.tvNoEntities.visibility =
                    if (entities.isEmpty()) View.VISIBLE else View.GONE

                binding.progressImport.visibility = View.GONE
                binding.importContent.visibility = View.VISIBLE
            }.onFailure {
                binding.progressImport.visibility = View.GONE
                binding.importContent.visibility = View.VISIBLE
                binding.tvImportConversation.text = "Histórico do WhatsApp"
                binding.tvImportSummary.text =
                    "Não foi possível ler o histórico: ${it.message ?: "formato inválido"}"
                binding.btnImport.isEnabled = false
            }
        }
    }

    private fun createEntityDialog() {
        val input = EditText(this).apply {
            hint = "Ex.: Cliente Alpha, Projeto Beta"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(48, 12, 48, 0)
        }

        AlertDialog.Builder(this)
            .setTitle("Criar nova entidade")
            .setMessage("O histórico será importado diretamente para esta entidade.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    lifecycleScope.launch {
                        val id = memory.createEntity(name)
                        selectedEntityId = id
                        val entities = db.memory().entitiesFlow().first()
                        entityAdapter.submit(entities)
                        binding.tvNoEntities.visibility = View.GONE
                        binding.btnImport.isEnabled = true
                    }
                }
            }
            .show()
    }

    private fun importSelected() {
        val loaded = payload ?: return
        val entityId = selectedEntityId ?: return

        binding.btnImport.isEnabled = false
        binding.btnCreateEntity.isEnabled = false
        binding.progressImport.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = runCatching { importPayload(loaded, entityId) }
            result.onSuccess {
                Toast.makeText(
                    this@ConversationImportActivity,
                    "Importação concluída: ${it.imported} novas, ${it.duplicates} duplicadas",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }.onFailure {
                binding.progressImport.visibility = View.GONE
                binding.btnImport.isEnabled = true
                binding.btnCreateEntity.isEnabled = true
                Toast.makeText(
                    this@ConversationImportActivity,
                    "Não foi possível importar: ${it.message ?: "erro desconhecido"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun prepareIntent(intent: Intent): ImportPayload = withContext(Dispatchers.IO) {
        val uris = extractUris(intent)
        val directText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        val sourceTexts = mutableListOf<Pair<String, String>>()
        var sourceLabel = "compartilhamento do WhatsApp"
        var conversation = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Histórico importado"

        for (uri in uris) {
            val displayName = displayName(uri) ?: "WhatsApp export"
            sourceLabel = displayName
            conversation = deriveConversationName(displayName)
            sourceTexts += readExportTexts(uri, displayName)
        }

        if (!directText.isNullOrBlank()) {
            sourceTexts += "texto compartilhado" to directText
        }

        require(sourceTexts.isNotEmpty()) { "nenhum arquivo ou texto recebido" }

        val messages = mutableListOf<PreparedMessage>()
        for ((sourceName, exportText) in sourceTexts) {
            val parsed = WhatsAppExportParser.parse(exportText)
            for (msg in parsed) {
                messages += PreparedMessage(sourceName, msg)
            }
        }

        require(messages.isNotEmpty()) {
            "0 mensagens reconhecidas. O formato deste export do WhatsApp ainda não foi identificado."
        }

        ImportPayload(
            conversation = conversation,
            sourceLabel = sourceLabel,
            messages = messages
        )
    }

    private suspend fun importPayload(
        payload: ImportPayload,
        entityId: Long
    ): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var duplicates = 0

        for (prepared in payload.messages) {
            val msg = prepared.message
            val fp = WhatsAppExportParser.fingerprint(
                conversation = payload.conversation,
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
                    sender = payload.conversation,
                    text = msg.text,
                    timestamp = msg.timestamp,
                    packageName = PACKAGE_IMPORTED,
                    sourceType = "WHATSAPP_EXPORT",
                    sourceRef = "${prepared.sourceName}#L${msg.sourceLine}",
                    author = msg.author,
                    fingerprint = fp
                )
            )
            if (id > 0L) imported++ else duplicates++
        }

        memory.linkConversation(
            entityId = entityId,
            packageName = PACKAGE_IMPORTED,
            sender = payload.conversation,
            role = "IMPORTED_HISTORY"
        )

        ImportResult(imported, duplicates)
    }

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    private fun readExportTexts(uri: Uri, displayName: String): List<Pair<String, String>> {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("arquivo inacessível: $displayName")

        val isZip =
            displayName.endsWith(".zip", ignoreCase = true) ||
                (bytes.size >= 4 &&
                    bytes[0] == 0x50.toByte() &&
                    bytes[1] == 0x4B.toByte() &&
                    bytes[2] == 0x03.toByte() &&
                    bytes[3] == 0x04.toByte())

        if (isZip) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val out = mutableListOf<Pair<String, String>>()
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".txt", ignoreCase = true)) {
                        val textBytes = zip.readBytes()
                        out += entry.name to textBytes.toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                require(out.isNotEmpty()) { "ZIP sem histórico .txt" }
                return out
            }
        }

        val text = BufferedReader(
            InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8)
        ).readText()
        return listOf(displayName to text)
    }

    private fun displayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun deriveConversationName(filename: String): String =
        filename
            .substringBeforeLast('.')
            .replace(Regex("""(?i)^WhatsApp Chat with\s+"""), "")
            .replace(Regex("""(?i)^Conversa do WhatsApp com\s+"""), "")
            .replace(Regex("""(?i)^Chat de WhatsApp con\s+"""), "")
            .replace(Regex("""(?i)^Conversa com\s+"""), "")
            .trim()
            .ifBlank { "Histórico importado" }

    data class PreparedMessage(
        val sourceName: String,
        val message: ImportedChatMessage
    )

    data class ImportPayload(
        val conversation: String,
        val sourceLabel: String,
        val messages: List<PreparedMessage>
    )

    data class ImportResult(val imported: Int, val duplicates: Int)

    companion object {
        const val PACKAGE_IMPORTED = "wa.keeper.import"
    }
}

private class ImportEntityAdapter(
    private val selectedId: () -> Long?,
    private val onSelect: (Long) -> Unit
) : RecyclerView.Adapter<ImportEntityAdapter.VH>() {
    private val items = mutableListOf<MemoryEntity>()

    fun submit(values: List<MemoryEntity>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        val name: TextView = card.findViewById(R.id.tvImportEntityName)
        val check: RadioButton = card.findViewById(R.id.rbImportEntity)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_import_entity, parent, false) as MaterialCardView
        return VH(card)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = selectedId() == item.id
        holder.check.setOnCheckedChangeListener { _, checked ->
            if (checked) onSelect(item.id)
        }
        holder.card.setOnClickListener { onSelect(item.id) }
    }
}
