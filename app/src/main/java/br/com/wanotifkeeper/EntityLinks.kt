package br.com.wanotifkeeper

/**
 * Contrato de escrita para vínculos de Entidades.
 * Sem alteração da política legada de atribuição e remoção de links.
 */
interface EntityLinks {
    suspend fun linkConversation(
        entityId: Long,
        packageName: String,
        sender: String,
        role: String = "CONVERSATION"
    ): Long

    suspend fun unlink(entityId: Long, packageName: String, sender: String)
}
