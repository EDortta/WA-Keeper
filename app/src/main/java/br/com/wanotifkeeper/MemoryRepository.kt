package br.com.wanotifkeeper

import android.content.Context

/**
 * Camada de memória local. O LLM nunca é a fonte de verdade: ele recebe apenas
 * contexto recuperado daqui, sempre rastreável até NotifEntity.id/sourceRef.
 */
class MemoryRepository(context: Context) {
    private val db = NotifDatabase.get(context.applicationContext)

    suspend fun createEntity(name: String, kind: String = "PERSON"): Long =
        db.memory().insertEntity(MemoryEntity(name = name.trim(), kind = kind.trim().uppercase()))

    suspend fun linkConversation(
        entityId: Long,
        packageName: String,
        sender: String,
        role: String = "CONVERSATION"
    ): Long = db.memory().upsertLink(
        EntityLinkEntity(
            entityId = entityId,
            packageName = packageName,
            sender = sender.trim(),
            role = role
        )
    )

    suspend fun resolveConversation(packageName: String, sender: String): MemoryEntity? {
        val link = db.memory().linkForConversation(packageName, sender) ?: return null
        return db.memory().entityById(link.entityId)
    }

    suspend fun ensureEntityForConversation(
        packageName: String,
        sender: String,
        kind: String = "PERSON"
    ): Long {
        val existing = db.memory().linkForConversation(packageName, sender)
        if (existing != null) return existing.entityId
        val entityId = createEntity(sender, kind)
        linkConversation(entityId, packageName, sender)
        return entityId
    }

    /**
     * Retorna contexto cronológico (mais antigo -> mais novo), embora a busca SQL
     * selecione primeiro os itens mais recentes/relevantes para limitar custo.
     */
    suspend fun retrieveContext(
        entityId: Long,
        query: String = "",
        limit: Int = 40
    ): List<NotifEntity> = db.memory()
        .contextForEntity(entityId, query.trim(), limit.coerceIn(1, 200))
        .sortedBy { it.timestamp }

    suspend fun trace(messageId: Long): NotifEntity? = db.dao().byId(messageId)
}
