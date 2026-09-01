package br.com.wanotifkeeper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.com.wanotifkeeper.databinding.ActivityScheduledBinding
import br.com.wanotifkeeper.databinding.ItemScheduledBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Enviar quando esta pessoa falar comigo" (EPIC 4 / #18).
 *
 * A mesma tela arma a mensagem e lista as já armadas daquela conversa — editar e
 * cancelar valem enquanto a mensagem estiver `PENDING`. Depois que ela entra em voo
 * ou sai, não há botão: a linha vira registro de auditoria, não rascunho.
 */
class ScheduledMessagesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduledBinding
    /** Retido para ser fechado em onDestroy — senão vaza a janela se a activity morrer aberta. */
    private var editDialog: AlertDialog? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    private val pkg by lazy { intent.getStringExtra(EXTRA_PKG) ?: "com.whatsapp" }
    private val sender by lazy { intent.getStringExtra(EXTRA_SENDER).orEmpty() }
    private val dao by lazy { NotifDatabase.get(this).scheduled() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduledBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (sender.isBlank()) { finish(); return }

        binding.tvConversation.text = sender
        binding.btnArm.setOnClickListener { arm() }

        lifecycleScope.launch {
            dao.forConversationFlow(pkg, sender).collectLatest { render(it) }
        }
    }

    private fun arm() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Escreva a mensagem primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            dao.insert(
                ScheduledMessageEntity(
                    packageName = pkg, sender = sender, text = text,
                    createdAt = now, updatedAt = now
                )
            )
            binding.etMessage.setText("")
            Toast.makeText(
                this@ScheduledMessagesActivity,
                "Armada. O app vai tentar enviar na próxima mensagem de $sender.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun render(items: List<ScheduledMessageEntity>) {
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.containerArmed.removeAllViews()
        items.forEach { item ->
            val row = ItemScheduledBinding.inflate(layoutInflater, binding.containerArmed, false)
            row.tvText.text = item.text
            row.tvState.text = describe(item)
            // PENDING: dá para editar e cancelar. Encerrada (SENT/FAILED/CANCELLED): só
            // dá para tirar da lista — sem isso, uma linha FAILED ficava lá para sempre.
            // CLAIMED não ganha botão: pode haver envio em voo.
            val encerrada = item.scheduledState in
                setOf(ScheduledState.SENT, ScheduledState.FAILED, ScheduledState.CANCELLED)
            row.rowActions.visibility =
                if (item.isEditable || encerrada) View.VISIBLE else View.GONE
            row.btnEdit.visibility = if (item.isEditable) View.VISIBLE else View.GONE
            row.btnCancel.text = if (item.isEditable) "Cancelar" else "Remover"
            row.btnEdit.setOnClickListener { promptEdit(item) }
            row.btnCancel.setOnClickListener { if (item.isEditable) cancel(item) else remove(item) }
            binding.containerArmed.addView(row.root)
        }
    }

    /**
     * O texto de estado não infla o que o app sabe. `SENT` significa que o mecanismo
     * de resposta do WhatsApp **aceitou** o envio — não que o destinatário leu — e a
     * frase diz exatamente isso. Falha aparece com o motivo, sem virar sucesso.
     */
    private fun describe(item: ScheduledMessageEntity): String = when (item.scheduledState) {
        ScheduledState.PENDING -> {
            val base = "Aguardando a próxima mensagem de $sender"
            if (item.attempts > 0) "$base · ${item.attempts} tentativa(s) sem sucesso: ${item.lastError}"
            else base
        }
        ScheduledState.CLAIMED -> "Enviando agora…"
        // Não diz "entregue": o que o app sabe é que o Android aceitou despachar a
        // resposta para o WhatsApp. Confirmação de entrega ao destinatário não existe
        // por este mecanismo, e inventá-la seria exatamente o que a #18 proíbe.
        ScheduledState.SENT ->
            "Envio despachado ao WhatsApp em ${item.sentAt?.let { fmt.format(Date(it)) } ?: "—"}" +
                " · o app não tem como confirmar a entrega"
        ScheduledState.FAILED ->
            "Não foi enviada após ${item.attempts} tentativa(s): ${item.lastError ?: "motivo não registrado"}"
        ScheduledState.CANCELLED -> "Cancelada"
    }

    private fun promptEdit(item: ScheduledMessageEntity) {
        val field = EditText(this).apply {
            setText(item.text)
            setPadding(48, 32, 48, 32)
        }
        editDialog?.dismiss()
        editDialog = AlertDialog.Builder(this)
            .setTitle("Editar mensagem armada")
            .setView(field)
            .setPositiveButton("Salvar") { _, _ ->
                val novo = field.text.toString().trim()
                if (novo.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    // O UPDATE só casa enquanto a linha está PENDING: se ela saiu entre
                    // abrir o diálogo e salvar, a edição simplesmente não pega — e a
                    // mensagem que já foi continua sendo a que foi.
                    val changed = dao.updateText(item.id, novo, System.currentTimeMillis())
                    if (changed == 0) {
                        Toast.makeText(
                            this@ScheduledMessagesActivity,
                            "Essa mensagem já saiu da fila — a edição não foi aplicada.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Voltar", null)
            .show()
    }

    private fun remove(item: ScheduledMessageEntity) {
        lifecycleScope.launch { dao.delete(item.id) }
    }

    override fun onDestroy() {
        editDialog?.dismiss()
        editDialog = null
        super.onDestroy()
    }

    private fun cancel(item: ScheduledMessageEntity) {
        lifecycleScope.launch {
            val changed = dao.cancel(item.id, System.currentTimeMillis())
            if (changed == 0) {
                Toast.makeText(
                    this@ScheduledMessagesActivity,
                    "Tarde demais: essa mensagem já saiu da fila.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
