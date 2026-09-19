package br.com.wanotifkeeper

/**
 * Evita que estados transitórios do WhatsApp dominem a leitura em voz alta.
 *
 * O RepostGuard protege a identidade da mensagem. Este guard é deliberadamente mais estreito:
 * atua somente no anúncio automático de estados que o WhatsApp pode republicar por vários
 * segundos (por exemplo "Sending…") e de localização. Assim não sacrificamos armazenamento
 * nem o botão PLAY manual.
 */
class AnnouncementGuard(
    private val sendingTtlMs: Long = SENDING_TTL_MS,
    private val locationTtlMs: Long = LOCATION_TTL_MS
) {
    private data class Seen(val at: Long, val ttl: Long)
    private val seen = LinkedHashMap<String, Seen>()

    @Synchronized
    fun shouldAnnounce(
        packageName: String,
        notificationKey: String,
        sender: String,
        text: String,
        now: Long
    ): Boolean {
        purge(now)
        val kind = semanticKind(text) ?: return true
        val ttl = if (kind == "sending") sendingTtlMs else locationTtlMs
        val key = "$packageName|$notificationKey|$sender|$kind"
        val previous = seen[key]
        if (previous != null && now - previous.at <= previous.ttl) return false

        seen[key] = Seen(now, ttl)
        if (seen.size > MAX_ENTRIES) seen.remove(seen.keys.first())
        return true
    }

    private fun semanticKind(text: String): String? {
        val normalized = text
            .trim()
            .lowercase()
            .replace('…', '.')
            .replace(Regex("\\s+"), " ")

        if (SENDING.matches(normalized)) return "sending"
        if (LOCATION.containsMatchIn(normalized)) return "location"
        return null
    }

    private fun purge(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            if (now - entry.at > entry.ttl) it.remove()
        }
    }

    companion object {
        const val SENDING_TTL_MS = 2 * 60_000L
        const val LOCATION_TTL_MS = 10 * 60_000L
        private const val MAX_ENTRIES = 300

        private val SENDING = Regex(
            "^(sending|enviando|enviando mensagem|enviando archivo|enviando arquivo)[.! ]*$",
            RegexOption.IGNORE_CASE
        )
        private val LOCATION = Regex(
            "📍|\\b(location|live location|localização|localizacao|localização em tempo real|" +
                "ubicación|ubicacion|ubicación en tiempo real)\\b",
            RegexOption.IGNORE_CASE
        )
    }
}
