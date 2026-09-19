package br.com.wanotifkeeper

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.com.wanotifkeeper.databinding.ActivityScheduledBinding
import br.com.wanotifkeeper.databinding.ItemScheduledBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScheduledMessagesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduledBinding
    private var confirmationDialog: AlertDialog? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    private val pkg by lazy { intent.getStringExtra(EXTRA_PKG) ?: "com.whatsapp" }
    private val sender by lazy { intent.getStringExtra(EXTRA_SENDER).orEmpty() }
    private val dao by lazy { NotifDatabase.get(this).scheduled() }

    private var editingId: Long? = null
    private var selectedScheduledAt: Long? = null
    private var selectedMediaUri: String? = null
    private var selectedMediaMimeType: String? = null
    private var selectedMediaName: String? = null

    private val pickAttachment = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selectedMediaUri = uri.toString()
        selectedMediaMimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        selectedMediaName = displayName(uri.toString()) ?: "Anexo"
        renderAttachmentSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduledBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (sender.isBlank()) { finish(); return }

        binding.tvConversation.text = sender
        binding.btnArm.setOnClickListener { save() }
        binding.btnCancelEdit.setOnClickListener { resetComposer() }
        binding.btnAttachment.setOnClickListener { pickAttachment.launch(arrayOf("*/*")) }
        binding.btnRemoveAttachment.setOnClickListener {
            selectedMediaUri = null
            selectedMediaMimeType = null
            selectedMediaName = null
            renderAttachmentSelection()
        }
        binding.btnDateTime.setOnClickListener { chooseDateTime() }
        binding.rgTrigger.setOnCheckedChangeListener { _, checkedId ->
            binding.btnDateTime.visibility =
                if (checkedId == binding.radioAtTime.id) View.VISIBLE else View.GONE
        }

        lifecycleScope.launch {
            dao.forConversationFlow(pkg, sender).collectLatest { render(it) }
        }
    }

    private fun save() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && selectedMediaUri == null) {
            Toast.makeText(this, "Escreva um texto ou escolha um anexo", Toast.LENGTH_SHORT).show()
            return
        }

        val trigger = if (binding.radioAtTime.isChecked) {
            ScheduledTrigger.AT_TIME
        } else {
            ScheduledTrigger.NEXT_INCOMING
        }
        val at = if (trigger == ScheduledTrigger.AT_TIME) selectedScheduledAt else null

        if (trigger == ScheduledTrigger.AT_TIME && at == null) {
            Toast.makeText(this, "Escolha a data e a hora", Toast.LENGTH_SHORT).show()
            return
        }
        if (at != null && at <= System.currentTimeMillis()) {
            Toast.makeText(this, "Escolha um horário futuro", Toast.LENGTH_SHORT).show()
            return
        }

        if (trigger == ScheduledTrigger.AT_TIME && !ensureExactAlarmAccess()) {
            return
        }

        if (selectedMediaUri != null && !ensureMediaAutomationAccess()) {
            return
        }

        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            val id = editingId
            val changed = if (id == null) {
                dao.insert(
                    ScheduledMessageEntity(
                        packageName = pkg,
                        sender = sender,
                        text = text,
                        triggerType = trigger.name,
                        scheduledAt = at,
                        mediaUri = selectedMediaUri,
                        mediaMimeType = selectedMediaMimeType,
                        mediaName = selectedMediaName,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                1
            } else {
                dao.updatePending(
                    id = id,
                    text = text,
                    triggerType = trigger.name,
                    scheduledAt = at,
                    mediaUri = selectedMediaUri,
                    mediaMimeType = selectedMediaMimeType,
                    mediaName = selectedMediaName,
                    now = now
                )
            }

            if (changed == 0) {
                Toast.makeText(
                    this@ScheduledMessagesActivity,
                    "Essa programação já saiu da fila e não pode mais ser editada.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            ScheduledMessageAlarmScheduler.reschedule(this@ScheduledMessagesActivity)
            val wasEditing = id != null
            resetComposer()
            Toast.makeText(
                this@ScheduledMessagesActivity,
                if (wasEditing) {
                    "Programação atualizada."
                } else if (trigger == ScheduledTrigger.AT_TIME) {
                    "Programada para ${fmt.format(Date(at!!))}."
                } else {
                    "Programada para a próxima mensagem de $sender."
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun ensureMediaAutomationAccess(): Boolean {
        if (MediaShareAutomation.isEnabled(this)) return true

        AlertDialog.Builder(this)
            .setTitle("Ativar envio automático de anexos")
            .setMessage(
                "Para enviar PDF, áudio, vídeo, Word, Excel e outros arquivos, " +
                    "o WA Keeper precisa da automação de mídia em Acessibilidade. " +
                    "Ela só atua enquanto há um anexo programado sendo despachado."
            )
            .setPositiveButton("Abrir Acessibilidade") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            .setNegativeButton("Agora não", null)
            .show()
        return false
    }

    private fun ensureExactAlarmAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true

        val alarm = getSystemService(AlarmManager::class.java)
        if (alarm.canScheduleExactAlarms()) return true

        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            android.net.Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(
                    this,
                    "Não foi possível abrir a permissão de alarmes exatos.",
                    Toast.LENGTH_LONG
                ).show()
            }

        Toast.makeText(
            this,
            "Autorize alarmes e lembretes e depois toque em Programar novamente.",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun chooseDateTime() {
        val seed = Calendar.getInstance().apply {
            timeInMillis = selectedScheduledAt ?: (System.currentTimeMillis() + 60 * 60_000L)
        }
        val dateDialog = DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val selected = Calendar.getInstance().apply {
                            set(Calendar.YEAR, year)
                            set(Calendar.MONTH, month)
                            set(Calendar.DAY_OF_MONTH, day)
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        selectedScheduledAt = selected
                        binding.btnDateTime.text = fmt.format(Date(selected))
                    },
                    seed.get(Calendar.HOUR_OF_DAY),
                    seed.get(Calendar.MINUTE),
                    true
                ).show()
            },
            seed.get(Calendar.YEAR),
            seed.get(Calendar.MONTH),
            seed.get(Calendar.DAY_OF_MONTH)
        )
        dateDialog.datePicker.minDate = System.currentTimeMillis() - 60_000L
        dateDialog.show()
    }

    private fun render(items: List<ScheduledMessageEntity>) {
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.containerArmed.removeAllViews()
        items.forEach { item ->
            val row = ItemScheduledBinding.inflate(layoutInflater, binding.containerArmed, false)
            row.tvText.text = buildString {
                if (item.text.isNotBlank()) append(item.text)
                if (item.hasMedia) {
                    if (isNotEmpty()) append("\n")
                    append("📎 ").append(item.mediaName ?: item.mediaMimeType ?: "Anexo")
                }
            }
            row.tvState.text = describe(item)

            val removable = item.scheduledState != ScheduledState.CLAIMED
            row.rowActions.visibility = if (removable) View.VISIBLE else View.GONE
            row.btnEdit.visibility = if (item.isEditable) View.VISIBLE else View.GONE
            row.btnEdit.setOnClickListener { edit(item) }
            row.btnCancel.text = "Excluir"
            row.btnCancel.setOnClickListener { remove(item) }

            val ended = item.scheduledState in
                setOf(ScheduledState.SENT, ScheduledState.FAILED, ScheduledState.CANCELLED)
            row.btnReuse.visibility = if (ended) View.VISIBLE else View.GONE
            row.btnReuse.setOnClickListener { reuse(item) }
            binding.containerArmed.addView(row.root)
        }
    }

    private fun describe(item: ScheduledMessageEntity): String = when (item.scheduledState) {
        ScheduledState.PENDING -> {
            val base = when (item.scheduledTrigger) {
                ScheduledTrigger.NEXT_INCOMING -> "Aguardando a próxima mensagem de $sender"
                ScheduledTrigger.AT_TIME ->
                    "Programada para ${item.scheduledAt?.let { fmt.format(Date(it)) } ?: "horário inválido"}"
            }
            when {
                item.lastError != null && item.attempts > 0 ->
                    "$base · ${item.attempts} tentativa(s) sem sucesso: ${item.lastError}"
                item.lastError != null -> "$base · ainda não foi possível enviar: ${item.lastError}"
                else -> base
            }
        }
        ScheduledState.CLAIMED -> "Enviando agora…"
        ScheduledState.SENT ->
            "Envio despachado ao WhatsApp em ${item.sentAt?.let { fmt.format(Date(it)) } ?: "—"}" +
                " · o app não tem como confirmar a entrega"
        ScheduledState.FAILED -> when (item.lastError) {
            STALE_CLAIM_REASON ->
                "Interrompida: $STALE_CLAIM_REASON. Confira a conversa antes de programar de novo."
            null -> "Não foi enviada após ${item.attempts} tentativa(s): motivo não registrado"
            else -> "Não foi enviada após ${item.attempts} tentativa(s): ${item.lastError}"
        }
        ScheduledState.CANCELLED -> "Cancelada"
    }

    private fun edit(item: ScheduledMessageEntity) {
        editingId = item.id
        binding.etMessage.setText(item.text)
        binding.etMessage.setSelection(item.text.length)
        selectedMediaUri = item.mediaUri
        selectedMediaMimeType = item.mediaMimeType
        selectedMediaName = item.mediaName
        selectedScheduledAt = item.scheduledAt

        if (item.scheduledTrigger == ScheduledTrigger.AT_TIME) {
            binding.radioAtTime.isChecked = true
            binding.btnDateTime.text = item.scheduledAt?.let { fmt.format(Date(it)) } ?: "Escolher data e hora"
        } else {
            binding.radioNextIncoming.isChecked = true
            binding.btnDateTime.text = "Escolher data e hora"
        }

        renderAttachmentSelection()
        binding.btnArm.text = "Salvar alterações"
        binding.btnCancelEdit.visibility = View.VISIBLE
        binding.scroller.smoothScrollTo(0, 0)
        binding.etMessage.requestFocus()
    }

    private fun reuse(item: ScheduledMessageEntity) {
        resetComposer()
        binding.etMessage.setText(item.text)
        binding.etMessage.setSelection(item.text.length)
        selectedMediaUri = item.mediaUri
        selectedMediaMimeType = item.mediaMimeType
        selectedMediaName = item.mediaName
        renderAttachmentSelection()
        binding.etMessage.requestFocus()
        binding.scroller.smoothScrollTo(0, 0)
        Toast.makeText(this, "Conteúdo copiado. Escolha o gatilho e programe novamente.", Toast.LENGTH_SHORT).show()
    }

    private fun remove(item: ScheduledMessageEntity) {
        confirmationDialog?.dismiss()
        confirmationDialog = AlertDialog.Builder(this)
            .setTitle(if (item.isEditable) "Excluir programação?" else "Remover registro?")
            .setMessage(
                if (item.isEditable) {
                    "Esta mensagem não será enviada."
                } else {
                    "O registro some da lista. O que já foi enviado não volta atrás."
                }
            )
            .setPositiveButton("Excluir") { _, _ ->
                lifecycleScope.launch {
                    val removed = dao.delete(item.id)
                    if (removed == 0) {
                        Toast.makeText(
                            this@ScheduledMessagesActivity,
                            "A mensagem está em envio e não pode ser excluída agora.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        if (editingId == item.id) resetComposer()
                        ScheduledMessageAlarmScheduler.reschedule(this@ScheduledMessagesActivity)
                    }
                }
            }
            .setNegativeButton("Voltar", null)
            .show()
    }

    private fun resetComposer() {
        editingId = null
        binding.etMessage.setText("")
        selectedMediaUri = null
        selectedMediaMimeType = null
        selectedMediaName = null
        selectedScheduledAt = null
        binding.radioNextIncoming.isChecked = true
        binding.btnDateTime.text = "Escolher data e hora"
        binding.btnArm.text = "Programar"
        binding.btnCancelEdit.visibility = View.GONE
        renderAttachmentSelection()
    }

    private fun renderAttachmentSelection() {
        binding.tvAttachment.text = selectedMediaName?.let { name ->
            val mime = selectedMediaMimeType ?: "tipo desconhecido"
            "📎 $name · $mime"
        } ?: "Sem anexo"
        binding.btnRemoveAttachment.visibility =
            if (selectedMediaUri == null) View.GONE else View.VISIBLE
    }

    private fun displayName(uriText: String): String? = runCatching {
        val uri = android.net.Uri.parse(uriText)
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else cursor.getString(index)
        }
    }.getOrNull()

    override fun onDestroy() {
        confirmationDialog?.dismiss()
        confirmationDialog = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_SENDER = "sender"

        fun intent(ctx: Context, packageName: String, sender: String): Intent =
            Intent(ctx, ScheduledMessagesActivity::class.java)
                .putExtra(EXTRA_PKG, packageName)
                .putExtra(EXTRA_SENDER, sender)
    }
}
