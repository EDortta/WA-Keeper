package br.com.wanotifkeeper

import android.content.Context

data class AiSuggestion(
    val entityId: Long,
    val packageName: String,
    val sender: String,
    val text: String,
    val sourceMessageIds: List<Long>
)

fun interface LocalSuggestionModel {
    suspend fun suggest(context: List<NotifEntity>, incomingText: String): String
}

/**
 * Orquestra memória + modelo local sem jamais enviar por conta própria.
 * A única mutação possível aqui é enfileirar uma sugestão já confirmada pelo usuário.
 */
class LocalAiAssistant(
    context: Context,
    private val model: LocalSuggestionModel
) {
    private val appContext = context.applicationContext
    private val db = NotifDatabase.get(appContext)
    private val memory = MemoryRepository(appContext)

    suspend fun suggest(
        packageName: String,
        sender: String,
        incomingText: String,
        contextLimit: Int = 40
    ): AiSuggestion {
        val entityId = memory.ensureEntityForConversation(packageName, sender)
        val contextMessages = memory.retrieveContext(
            entityId = entityId,
            query = "",
            limit = contextLimit
        )
        val text = model.suggest(contextMessages, incomingText).trim()
        require(text.isNotBlank()) { "modelo local retornou sugestão vazia" }

        return AiSuggestion(
            entityId = entityId,
            packageName = packageName,
            sender = sender,
            text = text,
            sourceMessageIds = contextMessages.map { it.id }
        )
    }

    /**
     * Persistência só após confirmação explícita da UI/voz.
     * Não dispara envio; apenas entrega ao agendador já existente.
     */
    suspend fun queueConfirmedSuggestion(
        suggestion: AiSuggestion,
        confirmedByUser: Boolean,
        scheduledAt: Long? = null
    ): Long {
        require(confirmedByUser) { "sugestão precisa de confirmação explícita" }
        val now = System.currentTimeMillis()
        val trigger = if (scheduledAt == null) {
            ScheduledTrigger.NEXT_INCOMING
        } else {
            ScheduledTrigger.AT_TIME
        }

        return db.scheduled().insert(
            ScheduledMessageEntity(
                packageName = suggestion.packageName,
                sender = suggestion.sender,
                text = suggestion.text,
                triggerType = trigger.name,
                scheduledAt = scheduledAt,
                createdAt = now,
                updatedAt = now,
                nextAttemptAt = 0L
            )
        )
    }
}
