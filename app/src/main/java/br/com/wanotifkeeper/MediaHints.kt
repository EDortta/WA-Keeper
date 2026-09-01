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

    /** O emoji que o WhatsApp prefixa no rótulo de foto. Vale mesmo com legenda depois. */
    private val IMAGE_EMOJI = Regex("📷|📸|🖼|🏞")

    /**
     * O rótulo de foto sozinho, sem frase em volta — inclusive na forma agrupada
     * ("2 fotos", "3 photos"). `matches` exige o texto inteiro: "me manda a foto" não casa.
     */
    private val IMAGE_LABEL = Regex(
        "(\\d+\\s+)?(fotos?|fotografias?|fotografías?|imagem|imagens|imagen|imágenes|" +
            "photos?|pictures?|images?)",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeVoiceMessage(text: String): Boolean = VOICE_HINT.containsMatchIn(text)

    fun looksLikeImageMessage(text: String): Boolean {
        if (IMAGE_EMOJI.containsMatchIn(text)) return true
        return IMAGE_LABEL.matches(text.trim())
    }
}
