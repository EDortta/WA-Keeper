package br.com.wanotifkeeper

object ConversationIdentity {
    fun canonicalSender(raw: String): String {
        var value = raw
            .removePrefix("WhatsApp: ")
            .trim()

        // WhatsApp may decorate group titles when several messages are pending:
        // "Open (3 mensagens): Luís Henrique" -> "Open"
        value = value.replace(
            Regex("""\s*\(\d+\s+mensage(?:m|ns)\)\s*:\s*.+$""", RegexOption.IGNORE_CASE),
            ""
        )

        // Reply previews may leak into the notification title:
        // "Open: ↪ Você..." -> "Open"
        value = value.replace(
            Regex("""\s*:\s*[↪↩↵].*$"""),
            ""
        )

        return value.trim().ifBlank { raw.trim() }
    }

    fun isVisibleHomeConversation(item: NotifEntity): Boolean =
        item.packageName != ConversationImportActivity.PACKAGE_IMPORTED &&
            item.sourceType != "WHATSAPP_EXPORT"

    fun sameConversation(a: String, b: String): Boolean =
        canonicalSender(a).equals(canonicalSender(b), ignoreCase = true)
}
