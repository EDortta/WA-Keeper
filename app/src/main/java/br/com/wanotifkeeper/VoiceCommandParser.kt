package br.com.wanotifkeeper

import java.text.Normalizer

/** "Leia as últimas N mensagens de X" já interpretado. */
data class VoiceCommand(val target: String, val count: Int)

data class ParsedVoiceCommand(val command: VoiceCommand, val accountOverride: String?)

/**
 * Interpreta a transcrição bruta do reconhecedor em um comando conhecido — ou não interpreta
 * nada. Sem wake word, qualquer frase dita durante a janela de ativação passa por aqui; a
 * política é nunca adivinhar: se não bater com um padrão reconhecido, retorna null e o
 * motor de voz ignora em silêncio, sem incomodar o motorista com fala alheia mal-entendida.
 */
object VoiceCommandParser {

    // Sem "^" no começo: depois de tirar a frase de override de conta ("pelo whatsapp
    // secundário, ..."), pode sobrar lixo textual antes do verbo (ex.: "pelo , leia...");
    // o "\b" evita casar "leia" dentro de outra palavra (ex.: "boleia").
    private val READ_LAST_PATTERN = Regex(
        "\\b(?:${VoiceGrammar.READ_LAST_VERBS.joinToString("|")})\\b\\s+(?:as\\s+)?(?:ultimas?\\s+)?" +
            "(?:(\\d+)\\s+)?mensagens?\\s+(?:de|do|da)\\s+(.+?)\\s*$"
    )
    private val WHAT_DID_X_SAY_PATTERN = Regex("\\bo\\s+que\\s+(.+?)\\s+mandou\\??\\s*$")

    fun parse(rawUtterance: String): ParsedVoiceCommand? {
        val hasOverride = VoiceGrammar.ACCOUNT_OVERRIDE_PHRASES.any { normalize(rawUtterance).contains(it) }
        var cleaned = normalize(rawUtterance)
        if (hasOverride) {
            VoiceGrammar.ACCOUNT_OVERRIDE_PHRASES.forEach { cleaned = cleaned.replace(it, " ") }
            cleaned = cleaned.trim().replace(Regex("\\s+"), " ")
        }
        val accountOverride = if (hasOverride) Prefs.PKG_BUSINESS else null

        READ_LAST_PATTERN.find(cleaned)?.let { m ->
            val count = m.groupValues[1].toIntOrNull()?.coerceIn(1, VoiceGrammar.MAX_READ_COUNT)
                ?: VoiceGrammar.DEFAULT_READ_COUNT
            val target = m.groupValues[2].trim()
            if (target.isNotEmpty()) return ParsedVoiceCommand(VoiceCommand(target, count), accountOverride)
        }

        WHAT_DID_X_SAY_PATTERN.find(cleaned)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotEmpty()) {
                return ParsedVoiceCommand(VoiceCommand(target, VoiceGrammar.DEFAULT_READ_COUNT), accountOverride)
            }
        }

        return null
    }

    /** Número falado ou dígito na resposta ao menu de desambiguação — restrito às opções oferecidas. */
    fun parseDisambiguationAnswer(rawUtterance: String, optionCount: Int): Int? {
        val norm = normalize(rawUtterance)
        Regex("""\d+""").find(norm)?.value?.toIntOrNull()?.let { if (it in 1..optionCount) return it }
        for ((word, n) in VoiceGrammar.NUMBER_WORDS) {
            if (n in 1..optionCount && Regex("\\b$word\\b").containsMatchIn(norm)) return n
        }
        return null
    }

    /** Minúsculas, sem acento, espaços colapsados — resiste a variação de STT sem mudar a lógica acima. */
    private fun normalize(s: String): String {
        val stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return stripped.lowercase().trim().replace(Regex("\\s+"), " ")
    }
}
