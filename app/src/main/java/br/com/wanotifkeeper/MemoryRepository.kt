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
        val direct = db.memory().linkForConversation(packageName, sender)
        if (direct != null) return db.memory().entityById(direct.entityId)

        val alias = findContactAliasMatch(sender)
        return if (alias != null) db.memory().entityById(alias.entityId) else null
    }

    suspend fun maybeLinkIncomingConversation(packageName: String, sender: String): Long? {
        val direct = db.memory().linkForConversation(packageName, sender)
        if (direct != null) return direct.entityId

        val alias = findContactAliasMatch(sender) ?: return null
        linkConversation(alias.entityId, packageName, sender, role = "CONVERSATION")
        return alias.entityId
    }

    private suspend fun findContactAliasMatch(sender: String): EntityLinkEntity? {
        val senderName = sender.trim().lowercase()
        val senderDigits = normalizePhone(sender)

        for (link in db.memory().linksByRole("CONTACT")) {
            val parts = decodeContactAlias(link.sender)
            val name = parts.first.trim().lowercase()
            val phoneDigits = normalizePhone(parts.second)

            val sameName = name.isNotBlank() && name == senderName
            val samePhone = senderDigits.length >= 8 && phoneDigits.length >= 8 &&
                (senderDigits == phoneDigits ||
                    senderDigits.endsWith(phoneDigits.takeLast(8)) ||
                    phoneDigits.endsWith(senderDigits.takeLast(8)))

            if (sameName || samePhone) return link
        }
        return null
    }

    suspend fun ensureEntityForConversation(
        packageName: String,
        sender: String,
        kind: String = "PERSON"
    ): Long {
        val existing = db.memory().linkForConversation(packageName, sender)
        if (existing != null) return existing.entityId

        val alias = findContactAliasMatch(sender)
        if (alias != null) {
            linkConversation(alias.entityId, packageName, sender)
            return alias.entityId
        }

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

    companion object {
        private const val CONTACT_SEPARATOR = "\u001F"

        fun encodeContactAlias(name: String, phone: String): String =
            name.trim() + CONTACT_SEPARATOR + phone.trim()

        fun decodeContactAlias(encoded: String): Pair<String, String> {
            val parts = encoded.split(CONTACT_SEPARATOR, limit = 2)
            return (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
        }

        fun contactAliasLabel(encoded: String): String {
            val (name, phone) = decodeContactAlias(encoded)
            return when {
                name.isNotBlank() && phone.isNotBlank() -> "$name · $phone"
                name.isNotBlank() -> name
                else -> phone
            }
        }

        private fun normalizePhone(value: String): String =
            value.filter { it.isDigit() }
    }
}
