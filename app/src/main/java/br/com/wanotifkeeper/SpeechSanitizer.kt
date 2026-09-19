package br.com.wanotifkeeper

/**
 * Reduz dados estruturados que são úteis na tela, mas péssimos quando lidos por TTS.
 * O texto original nunca é alterado no banco; isto é apenas uma projeção para fala automática.
 */
object SpeechSanitizer {

    private val url = Regex(
        """(?i)\b(?:https?://|www\.)\S+|\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s]*)?"""
    )
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")

    // Pix Copia e Cola (EMV): normalmente começa em 000201 e contém BR.GOV.BCB.PIX.
    // Limitamos a um token/trecho longo para não engolir texto humano que venha antes/depois.
    private val pixPayload = Regex(
        """(?is)(?:000201[0-9A-Za-z.\-/:*+_]{35,}|[0-9A-Za-z.\-/:*+_]{20,}BR\.GOV\.BCB\.PIX[0-9A-Za-z.\-/:*+_]{20,})"""
    )

    // Códigos de barras/linhas digitáveis e identificadores gigantes sem valor acústico.
    private val longCode = Regex("""(?<!\w)(?:[0-9][0-9 .\-]{35,}[0-9]|[A-Za-z0-9]{45,})(?!\w)""")

    fun forAutomaticSpeech(text: String): String {
        if (text.isBlank()) return text

        var out = text
        var hadPix = false
        var hadUrl = false
        var hadEmail = false
        var hadCode = false

        out = pixPayload.replace(out) {
            hadPix = true
            " "
        }

        out = url.replace(out) {
            hadUrl = true
            " "
        }

        out = email.replace(out) {
            hadEmail = true
            " "
        }

        out = longCode.replace(out) {
            hadCode = true
            " "
        }

        out = out
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .trim(',', ';', ':', '-', '.', ' ')

        val notices = mutableListOf<String>()
        if (hadPix) notices += "Chave Pix anexa."
        if (hadUrl) notices += "URL disponível para visita."
        if (hadEmail) notices += "Endereço de e-mail disponível."
        if (hadCode) notices += "Código disponível."

        return buildString {
            if (out.isNotBlank()) {
                append(out)
                if (out.lastOrNull() !in listOf('.', '!', '?')) append('.')
            }
            if (notices.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(notices.joinToString(" "))
            }
        }.ifBlank { text }
    }
}
