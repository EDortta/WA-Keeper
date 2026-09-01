package br.com.wanotifkeeper

/**
 * Regra pura (sem nenhuma API do Android) de quando o texto de uma notificação do WhatsApp
 * indica mídia. Extraída do [MediaVault] pelo mesmo motivo que [VoiceGateDecision] foi
 * extraída do serviço: é a decisão que precisa de teste, e ela não depende do Android.
 *
 * O ponto delicado é a imagem. O fallback de imagem varre o diretório de mídia e anexa o
 * arquivo mais próximo no tempo; se o gatilho for uma palavra solta dentro de uma frase, uma
 * mensagem de TEXTO como "me manda a foto" passa a puxar uma imagem alheia que por acaso
 * chegou na mesma janela. A #17 proíbe isso com todas as letras:
 * *"Não anexar imagem antiga a uma mensagem de texto por coincidência temporal."*
 *
 * Por isso o indício de imagem não é "contém a palavra foto". É uma de duas coisas:
 *  - o **emoji** que o WhatsApp põe no rótulo da mídia (📷/🖼), o sinal confiável — do mesmo
 *    jeito que o 🎤 é para voz; ou
 *  - o texto ser **só o rótulo**, sozinho ("Foto", "2 fotos", "Photo"), que é o que o
 *    WhatsApp manda quando não há legenda. Uma frase que contém a palavra não conta.
 */
object MediaHints {

    /** Anchor de mensagem de voz nas notificações (pt/es/en). O 🎤 é o sinal mais confiável. */
    private val VOICE_HINT = Regex(
        "🎤|🎙|voice message|mensagem de voz|mensaje de voz",
        RegexOption.IGNORE_CASE
    )

    /**
     * O emoji de foto **no início** do texto. Ancorado de propósito: o WhatsApp põe o emoji
     * como prefixo do rótulo, então "📷 Foto" casa e "comprei uma câmera nova 📸" — emoji
     * digitado por uma pessoa no meio de uma frase — não casa. Sem a âncora, esse era o
     * caminho incondicional para anexar uma imagem alheia a uma mensagem de texto.
     */
    private val IMAGE_EMOJI_PREFIX = Regex("^(📷|📸|🖼|🏞)")

    /**
     * O rótulo de foto sozinho, sem frase em volta — inclusive na forma agrupada
     * ("2 fotos", "3 photos"). `matches` exige o texto inteiro: "me manda a foto" não casa.
     *
     * `(?iu)` em vez de [RegexOption.IGNORE_CASE]: a opção do Kotlin é só
     * `Pattern.CASE_INSENSITIVE`, sem `UNICODE_CASE`, e sem isso "IMÁGENES" não casa com
     * `imágenes`. O separador aceita NBSP porque o WhatsApp usa U+00A0 em "2 fotos".
     */
    private val IMAGE_LABEL = Regex(
        "(?iu)(\\d+[\\s\\u00A0]+)?(fotos?|fotografias?|fotografías?|imagem|imagens|" +
            "imagen|imágenes|photos?|pictures?|images?)"
    )

    /**
     * Em grupo o texto da notificação vem como "Fulano: corpo". Sem tirar esse prefixo, o
     * ramo de rótulo (o que salva as versões do WhatsApp que NÃO mandam o emoji) nunca
     * dispara em grupo — que é o caso de uso mais comum.
     *
     * S1 do concílio: quando o strip era incondicional, ele desfazia parte da âncora que o
     * ramo de rótulo existe para ter. Em conversa 1-a-1 o WhatsApp NÃO prefixa o remetente,
     * então qualquer `"algo: "` no início é texto que uma pessoa digitou — e `"Ana: fotos"`
     * voltava a varrer o diretório e anexar foto alheia a uma mensagem de TEXTO, exatamente
     * o que a #17 proíbe. Por isso o strip agora exige evidência de que a conversa é de
     * grupo (`EXTRA_IS_GROUP_CONVERSATION`), em vez de casar qualquer `": "`.
     */
    private val SENDER_PREFIX = Regex("^[^:\n]{1,40}:[\\s\\u00A0]+")

    /** Marcas invisíveis (bidi, BOM) que o `trim()` do Kotlin não remove. */
    private val INVISIBLE = charArrayOf('\u200E', '\u200F', '\u202A', '\u202C', '\uFEFF')

    fun looksLikeVoiceMessage(text: String): Boolean = VOICE_HINT.containsMatchIn(text)

    /**
     * @param isGroup a notificação declara ser de conversa em grupo. Só nesse caso o prefixo
     *   "Fulano: " é removido antes de testar o rótulo — ver [SENDER_PREFIX].
     */
    fun looksLikeImageMessage(text: String, isGroup: Boolean = false): Boolean {
        val normalized = normalize(text)
        val body = if (isGroup) stripSenderPrefix(normalized) else normalized
        if (IMAGE_EMOJI_PREFIX.containsMatchIn(body)) return true
        return IMAGE_LABEL.matches(body)
    }

    private fun normalize(text: String): String =
        text.trim().trim(*INVISIBLE).trim()

    private fun stripSenderPrefix(text: String): String =
        SENDER_PREFIX.find(text)?.let { normalize(text.removeRange(it.range)) } ?: text
}
