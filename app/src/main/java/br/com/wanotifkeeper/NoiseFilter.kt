package br.com.wanotifkeeper

/**
 * Reconhece notificações de serviço do WhatsApp que não são mensagens reais.
 * As notificações chegam no idioma do aparelho — cobrimos pt, es e en.
 */
object NoiseFilter {

    // Título de notificação de backup: "Backup em andamento", "Backup in progress",
    // "Copia de seguridad en curso", "Restaurando backup"...
    private val backupTitle = Regex(
        "backup|copia de seguridad|cópia de segurança|copia de segurança",
        RegexOption.IGNORE_CASE
    )

    // "Mensagem apagada" — a mensagem original já foi capturada antes, então o
    // aviso de apagamento é ruído.
    private val deletedText = Regex(
        "^(esta |essa |this )?(mensagem|mensaje|message)( foi| fue| was| se)? ?(apagada|eliminado|eliminó|deleted|apagou|borrado)\\.?$" +
            "|^(você|voce|you|tú|tu) (apagou|deleted|eliminaste)" +
            "|^se eliminó este mensaje",
        RegexOption.IGNORE_CASE
    )

    // Notificação de foreground service enquanto o WhatsApp sincroniza.
    private val checkingText = Regex(
        "(procurando|verificando|buscando|checking for|looking for|comprobando)" +
            ".*(novas mensagens|mensajes nuevos|nuevos mensajes|new messages)",
        RegexOption.IGNORE_CASE
    )

    // Resumos agregados: "5 mensagens de 2 contatos", "3 new messages", "Novas mensagens:"
    private val summaryText = Regex(
        "^\\d+ (mensagens?|mensajes?) de \\d+ (contatos?|contactos?|chats?)" +
            "|^\\d+ new messages?" +
            "|^(novas mensagens|nuevos mensajes|new messages):",
        RegexOption.IGNORE_CASE
    )

    /** true quando a notificação não deve ser guardada. */
    fun isNoise(title: String, text: String): Boolean {
        val t = title.trim()
        val x = text.trim()
        if (x.isEmpty()) return true
        if (backupTitle.containsMatchIn(t)) return true
        if (deletedText.containsMatchIn(x)) return true
        if (checkingText.containsMatchIn(x) || checkingText.containsMatchIn(t)) return true
        if (summaryText.containsMatchIn(x)) return true
        return false
    }
}
