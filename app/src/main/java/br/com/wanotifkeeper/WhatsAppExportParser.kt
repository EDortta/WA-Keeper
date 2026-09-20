package br.com.wanotifkeeper

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

data class ImportedChatMessage(
    val author: String,
    val text: String,
    val timestamp: Long,
    val sourceLine: Int
)

object WhatsAppExportParser {
    private val linePatterns = listOf(
        Regex("""^\[?(\d{1,2}/\d{1,2}/\d{2,4})[,]?\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[APMapm]{2})?)\]?\s*[-–—]\s*([^:]+):\s?(.*)$"""),
        Regex("""^\[(\d{1,2}/\d{1,2}/\d{2,4})[,]?\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s*[APMapm]{2})?)\]\s*([^:]+):\s?(.*)$""")
    )

    private val dateFormats = listOf(
        "d/M/yy H:mm",
        "d/M/yyyy H:mm",
        "d/M/yy H:mm:ss",
        "d/M/yyyy H:mm:ss",
        "M/d/yy h:mm a",
        "M/d/yyyy h:mm a",
        "M/d/yy h:mm:ss a",
        "M/d/yyyy h:mm:ss a"
    )

    fun parse(text: String): List<ImportedChatMessage> {
        val normalized = text
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace("\r\n", "\n")

        val out = mutableListOf<ImportedChatMessage>()
        var current: ImportedChatMessage? = null

        normalized.lineSequence().forEachIndexed { index, raw ->
            val line = raw.removePrefix("\uFEFF").trimEnd()
            val match = linePatterns.firstNotNullOfOrNull { it.matchEntire(line) }
            if (match != null) {
                current?.let(out::add)
                val (date, time, author, body) = match.destructured
                val ts = parseTimestamp("$date $time") ?: 0L
                current = ImportedChatMessage(
                    author = author.trim(),
                    text = body.trim(),
                    timestamp = ts,
                    sourceLine = index + 1
                )
            } else if (current != null) {
                current = current!!.copy(text = current!!.text + "\n" + line)
            }
        }

        current?.let(out::add)
        return out.filter { it.text.isNotBlank() && it.timestamp > 0L }
    }

    private fun parseTimestamp(value: String): Long? {
        val normalized = value
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        for (pattern in dateFormats) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            runCatching { parser.parse(normalized)?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    fun fingerprint(
        conversation: String,
        author: String,
        timestamp: Long,
        text: String
    ): String {
        val canonical = listOf(
            conversation.trim().lowercase(),
            author.trim().lowercase(),
            timestamp.toString(),
            text.trim().replace(Regex("""\s+"""), " ")
        ).joinToString("\u001F")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
