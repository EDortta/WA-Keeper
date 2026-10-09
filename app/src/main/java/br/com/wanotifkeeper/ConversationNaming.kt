package br.com.wanotifkeeper

/**
 * Contrato interno do domínio Conversas.
 *
 * Identificadores externos (packageName, conversationKey) são referências,
 * não garantias de identidade imutável. Os consumidores não precisam conhecer
 * as regras de normalização nem a representação usada para agrupamento.
 *
 * A API preserva os tipos legados durante a migração incremental. A adoção
 * de tipos dedicados dependerá de análise de compatibilidade dos consumidores.
 */
interface ConversationNaming {
    /** Normaliza o nome exibido sem resolver a identidade de um contato. */
    fun canonicalSender(raw: String, packageName: String? = null): String

    /** Chave de agrupamento para a representação de conversa já persistida. */
    fun groupKey(packageName: String, sender: String, conversationKey: String?): String
}
