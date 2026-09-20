package br.com.wanotifkeeper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import br.com.wanotifkeeper.databinding.ActivityEntityDetailBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EntityDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEntityDetailBinding
    private val db by lazy { NotifDatabase.get(this) }
    private val memory by lazy { MemoryRepository(this) }
    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    private var entityId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEntityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        entityId = intent.getLongExtra(EXTRA_ENTITY_ID, 0L)
        if (entityId == 0L) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLinkConversation.setOnClickListener { showLinkConversationDialog() }
        binding.btnAsk.setOnClickListener { askMemory() }
        binding.questionField.setOnEditorActionListener { _, _, _ ->
            askMemory()
            true
        }

        loadEntity()
    }

    override fun onResume() {
        super.onResume()
        loadEntity()
    }

    private fun loadEntity() {
        lifecycleScope.launch {
            val entity = db.memory().entityById(entityId) ?: return@launch
            binding.tvEntityTitle.text = entity.name
            renderLinks()
        }
    }

    private suspend fun renderLinks() {
        val links = db.memory().linksForEntity(entityId)
        binding.tvLinkedConversations.text =
            if (links.isEmpty()) {
                "Nenhuma conversa associada ainda."
            } else {
                links.joinToString("\n") { link ->
                    val source = when (link.packageName) {
                        "com.whatsapp.w4b" -> "WhatsApp Business"
                        "com.whatsapp" -> "WhatsApp"
                        "wa.keeper.import" -> "Importado"
                        else -> link.packageName
                    }
                    "• ${link.sender} — $source"
                }
            }
    }

    private fun showLinkConversationDialog() {
        lifecycleScope.launch {
            val all = db.dao().getAll()
                .distinctBy { it.packageName to it.sender }
                .sortedWith(compareBy<NotifEntity> { it.sender.lowercase() }.thenBy { it.packageName })

            if (all.isEmpty()) {
                AlertDialog.Builder(this@EntityDetailActivity)
                    .setTitle("Sem conversas")
                    .setMessage("Ainda não há conversas retidas para associar.")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            val current = db.memory().linksForEntity(entityId)
                .map { it.packageName to it.sender }
                .toSet()

            val labels = all.map {
                val source = when (it.packageName) {
                    "com.whatsapp.w4b" -> "Business"
                    "com.whatsapp" -> "WhatsApp"
                    "wa.keeper.import" -> "Importado"
                    else -> it.packageName
                }
                "${it.sender}  ·  $source"
            }.toTypedArray()

            val checked = BooleanArray(all.size) { i ->
                (all[i].packageName to all[i].sender) in current
            }

            AlertDialog.Builder(this@EntityDetailActivity)
                .setTitle("Associar conversas")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar") { _, _ ->
                    lifecycleScope.launch {
                        all.forEachIndexed { index, conversation ->
                            val pair = conversation.packageName to conversation.sender
                            val isLinkedHere = pair in current
                            when {
                                checked[index] && !isLinkedHere ->
                                    memory.linkConversation(
                                        entityId,
                                        conversation.packageName,
                                        conversation.sender
                                    )
                                !checked[index] && isLinkedHere ->
                                    db.memory().unlink(
                                        entityId,
                                        conversation.packageName,
                                        conversation.sender
                                    )
                            }
                        }
                        renderLinks()
                    }
                }
                .show()
        }
    }

    private fun askMemory() {
        val question = binding.questionField.text.toString().trim()
        if (question.isBlank()) return

        binding.progressAsk.visibility = View.VISIBLE
        binding.tvAnswer.text = "Pesquisando na memória local…"

        lifecycleScope.launch {
            val messages = memory.retrieveContext(entityId, query = "", limit = 200)
            val result = MemoryQuestionEngine.answer(question, messages, fmt)

            binding.progressAsk.visibility = View.GONE
            binding.tvAnswer.text = result.answer
            binding.tvSources.text = result.sources
            binding.tvSources.visibility = if (result.sources.isBlank()) View.GONE else View.VISIBLE
        }
    }

    companion object {
        private const val EXTRA_ENTITY_ID = "entity_id"

        fun intent(context: Context, entityId: Long) =
            Intent(context, EntityDetailActivity::class.java)
                .putExtra(EXTRA_ENTITY_ID, entityId)
    }
}

data class MemoryAnswer(
    val answer: String,
    val sources: String
)

object MemoryQuestionEngine {
    private val stopWords = setOf(
        "a","o","as","os","um","uma","de","da","do","das","dos","e","em","no","na",
        "nos","nas","para","por","com","que","qual","quais","quem","quando","onde","como",
        "foi","era","tem","tinha","me","eu","ele","ela","isso","isto","sobre"
    )

    fun answer(
        question: String,
        messages: List<NotifEntity>,
        fmt: SimpleDateFormat
    ): MemoryAnswer {
        if (messages.isEmpty()) {
            return MemoryAnswer(
                "Ainda não há mensagens associadas a esta entidade.",
                ""
            )
        }

        val terms = question.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stopWords }
            .distinct()

        val scored = messages.map { message ->
            val hay = "${message.sender} ${message.author.orEmpty()} ${message.text}".lowercase()
            val hits = terms.sumOf { term ->
                when {
                    hay.contains(term) -> 3
                    hay.split(Regex("\\s+")).any { it.startsWith(term.take(4)) } -> 1
                    else -> 0
                }
            }
            message to hits
        }

        val relevant = scored
            .filter { terms.isEmpty() || it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<NotifEntity, Int>> { it.second }
                    .thenByDescending { it.first.timestamp }
            )
            .take(8)
            .map { it.first }

        if (relevant.isEmpty()) {
            return MemoryAnswer(
                "Não encontrei nada claramente relacionado a essa pergunta na memória desta entidade.",
                "Tente usar nomes, assunto ou palavras que apareceram na conversa."
            )
        }

        val first = relevant.first()
        val date = fmt.format(Date(first.timestamp))
        val author = first.author?.takeIf { it.isNotBlank() } ?: first.sender

        val answer = if (relevant.size == 1) {
            "Encontrei uma referência direta: em $date, $author: “${first.text.take(320)}”"
        } else {
            "Encontrei ${relevant.size} referências relacionadas. A mais forte é de $date, $author: “${first.text.take(320)}”"
        }

        val sources = relevant.joinToString("\n\n") { item ->
            val itemDate = fmt.format(Date(item.timestamp))
            val itemAuthor = item.author?.takeIf { it.isNotBlank() } ?: item.sender
            "• $itemDate — $itemAuthor\n${item.text.take(260)}"
        }

        return MemoryAnswer(answer, sources)
    }
}
