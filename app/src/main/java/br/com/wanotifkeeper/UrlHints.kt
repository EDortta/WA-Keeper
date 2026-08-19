package br.com.wanotifkeeper

import java.net.URI

/**
 * Resume URLs na leitura em voz alta sem baixar nada — só olha o domínio do link.
 * O app promete "zero rede" (ver docs/index.html); isto existe pra cumprir essa
 * promessa mesmo quando a mensagem tem um link, em vez de simplesmente lê-lo por
 * extenso (o que também soa péssimo em voz alta).
 */
object UrlHints {

    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** Pontuação de frase que às vezes gruda no fim de uma URL copiada/colada. */
    private val TRAILING_PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', ')', ']', '"', '\'')

    private val DOMAIN_LABELS = listOf(
        "youtube.com" to "link do YouTube",
        "youtu.be" to "link do YouTube",
        "wa.me" to "link do WhatsApp",
        "whatsapp.com" to "link do WhatsApp",
        "instagram.com" to "link do Instagram",
        "facebook.com" to "link do Facebook",
        "fb.watch" to "link do Facebook",
        "twitter.com" to "link do Twitter",
        "x.com" to "link do Twitter",
        "maps.google.com" to "link do Google Maps",
        "drive.google.com" to "link do Google Drive",
        "docs.google.com" to "link do Google Docs",
        "tiktok.com" to "link do TikTok",
        "spotify.com" to "link do Spotify"
    )

    sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Link(val label: String) : Segment()
    }

    /** Rótulo curto pro domínio de uma URL — nunca acessa a rede, só olha o texto do link. */
    fun describe(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return "link"
        val match = DOMAIN_LABELS.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
        return match?.second ?: "link"
    }

    /** Quebra o texto em trechos normais e trechos de URL (já resumidos), na ordem em que aparecem. */
    fun segments(text: String): List<Segment> {
        val matches = URL_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return listOf(Segment.Text(text))

        val result = mutableListOf<Segment>()
        var cursor = 0
        for (m in matches) {
            if (m.range.first > cursor) {
                val before = text.substring(cursor, m.range.first).trim()
                if (before.isNotEmpty()) result.add(Segment.Text(before))
            }
            val url = m.value.trimEnd { it in TRAILING_PUNCTUATION }
            result.add(Segment.Link(describe(url)))
            cursor = m.range.last + 1
        }
        if (cursor < text.length) {
            val after = text.substring(cursor).trim()
            if (after.isNotEmpty()) result.add(Segment.Text(after))
        }
        return result
    }
}
