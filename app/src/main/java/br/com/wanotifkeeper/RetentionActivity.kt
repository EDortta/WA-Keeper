package br.com.wanotifkeeper

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.com.wanotifkeeper.databinding.ActivityRetentionBinding
import kotlinx.coroutines.launch

/** Configuração de retenção de uma conversa (por remetente). */
class RetentionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRetentionBinding
    private lateinit var sender: String

    private val units = listOf(
        "horas" to RetentionPolicy.HOUR_MS,
        "dias" to RetentionPolicy.DAY_MS,
        "semanas" to RetentionPolicy.WEEK_MS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRetentionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        sender = intent.getStringExtra(EXTRA_SENDER).orEmpty()
        if (sender.isBlank()) { finish(); return }
        binding.tvSender.text = sender

        // Negação de áudio por contato — aplica na hora, independente do botão Salvar.
        binding.swAudioBlock.isChecked = Prefs.isAudioBlocked(this, sender)
        binding.swAudioBlock.setOnCheckedChangeListener { _, checked ->
            Prefs.setAudioBlocked(this, sender, checked)
            if (checked) lifecycleScope.launch {
                // Apaga os áudios já guardados deste contato; a retenção varre os arquivos órfãos.
                NotifDatabase.get(this@RetentionActivity).dao().clearAudioForSender(sender)
                Retention.purge(this@RetentionActivity, System.currentTimeMillis())
            }
        }

        binding.spUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, units.map { it.first }
        )

        binding.swForever.setOnCheckedChangeListener { _, _ -> refreshEnabled() }
        binding.rgMode.setOnCheckedChangeListener { _, _ -> refreshEnabled() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnWipe.setOnClickListener { confirmWipe() }

        lifecycleScope.launch { load() }
    }

    private suspend fun load() {
        val cfg = NotifDatabase.get(this).settings().get(sender)
        when (cfg?.retentionMode) {
            null -> binding.rbDefault.isChecked = true
            RetentionMode.FOREVER -> binding.swForever.isChecked = true
            RetentionMode.NEVER -> binding.rbNever.isChecked = true
            RetentionMode.CUSTOM -> {
                binding.rbCustom.isChecked = true
                // Escolhe a maior unidade que divide exatamente a duração guardada.
                val idx = units.indexOfLast { cfg.durationMillis % it.second == 0L }.coerceAtLeast(0)
                binding.spUnit.setSelection(idx)
                binding.etAmount.setText((cfg.durationMillis / units[idx].second).toString())
            }
        }
        refreshEnabled()
    }

    /** "Sempre" desliga o resto; "Personalizado" é o único que usa quantidade + unidade. */
    private fun refreshEnabled() {
        val forever = binding.swForever.isChecked
        binding.rgMode.alpha = if (forever) 0.4f else 1f
        for (i in 0 until binding.rgMode.childCount) {
            binding.rgMode.getChildAt(i).isEnabled = !forever
        }
        val custom = !forever && binding.rbCustom.isChecked
        binding.rowCustom.visibility = if (custom) View.VISIBLE else View.GONE
        binding.tvForeverHint.visibility = if (forever) View.VISIBLE else View.GONE

        binding.tvExplain.text = when {
            forever -> "Mensagens desta conversa nunca são apagadas automaticamente."
            binding.rbNever.isChecked -> "Mensagens desta conversa não são guardadas."
            custom -> "Mensagens mais antigas que o prazo são apagadas automaticamente."
            else -> "Padrão: 6 dias — três vezes a janela em que o WhatsApp ainda permite " +
                "apagar para todos. Fora dela a mensagem não some mais no WhatsApp."
        }
    }

    private fun save() {
        val settings = when {
            binding.swForever.isChecked ->
                ConversationSettings(sender, RetentionMode.FOREVER.name, 0)

            binding.rbNever.isChecked ->
                ConversationSettings(sender, RetentionMode.NEVER.name, 0)

            binding.rbCustom.isChecked -> {
                val amount = binding.etAmount.text.toString().toLongOrNull() ?: 0L
                if (amount <= 0) {
                    Toast.makeText(this, "Informe uma quantidade maior que zero", Toast.LENGTH_SHORT).show()
                    return
                }
                val unit = units[binding.spUnit.selectedItemPosition].second
                ConversationSettings(sender, RetentionMode.CUSTOM.name, amount * unit)
            }

            else -> null   // padrão: sem linha de config
        }

        lifecycleScope.launch {
            val db = NotifDatabase.get(this@RetentionActivity)
            if (settings == null) db.settings().delete(sender) else db.settings().upsert(settings)
            Retention.purge(this@RetentionActivity, System.currentTimeMillis())
            Toast.makeText(this@RetentionActivity, "Salvo", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("Apagar histórico")
            .setMessage("Remover todas as mensagens guardadas de \"$sender\"? Isso não pode ser desfeito.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar") { _, _ ->
                lifecycleScope.launch {
                    NotifDatabase.get(this@RetentionActivity).dao().deleteSender(sender)
                    Retention.purge(this@RetentionActivity, System.currentTimeMillis())
                    Toast.makeText(this@RetentionActivity, "Histórico apagado", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_SENDER = "sender"
    }
}
