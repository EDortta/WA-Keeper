package br.com.wanotifkeeper

import java.text.Normalizer

/** "Leia as últimas N mensagens de X" já interpretado. */
data class VoiceCommand(val target: String, val count: Int)

data class ParsedVoiceCommand(val command: VoiceCommand, val accountOverride: String?)

/**
 * Interpreta a transcrição bruta do reconhecedor em um comando conhecido — ou não interpreta
 * nada. Exige a palavra de ativação ("Godofredo") em algum lugar da frase antes de tentar
 * qualquer padrão de comando — sem ela, mesmo uma frase que bateria é ignorada. A política
 * geral é nunca adivinhar: se não bater, retorna null e o motor de voz ignora em silêncio,
 * sem incomodar o motorista com fala alheia mal-entendida.
 */
object VoiceCommandParser {

    private val WAKE_WORD_PATTERN = Regex("\\b(?:${VoiceGrammar.WAKE_WORD_ALIASES.joinToString("|")})\\b")

    // Sem "^" no começo: depois de tirar a palavra de ativação e a frase de override de conta
    // ("pelo whatsapp secundário, ..."), pode sobrar lixo textual antes do verbo (ex.:
    // "pelo , leia..."); o "\b" evita casar "leia" dentro de outra palavra (ex.: "boleia").
    // O ".*?" entre o verbo e "mensagens" tolera fala natural ("lê PARA MIM as últimas
    // mensagens de..." / "lê PRA MIM..."), em vez de exigir a sequência exata "as últimas".
    private val READ_LAST_PATTERN = Regex(
        "\\b(?:${VoiceGrammar.READ_LAST_VERBS.joinToString("|")})\\b(.*?)\\bmensagens?\\b\\s*(?:de|do|da)\\s+(.+?)\\s*$"
    )
    private val WHAT_DID_X_SAY_PATTERN = Regex("\\bo\\s+que\\s+(.+?)\\s+mandou\\??\\s*$")

    /** A palavra de ativação está em algum lugar da frase — mesmo sozinha, sem comando junto. */
    fun hasWakeWord(rawUtterance: String): Boolean = WAKE_WORD_PATTERN.containsMatchIn(normalize(rawUtterance))

    /**
     * Tira a palavra de ativação da frase (normalizada) e devolve o que sobra — null se a
     * palavra não estiver presente, string vazia se só ela foi dita. Exposto (não privado) pra
     * o motor de voz conseguir diferenciar "só a palavra, ainda esperando o resto" (fica quieto,
     * arma a janela de graça) de "palavra + algo mais que não bateu com nenhum comando" (fala
     * que não entendeu) — sem isso os dois casos pareciam idênticos de fora.
     */
    fun remainderAfterWakeWord(rawUtterance: String): String? {
        val normalized = normalize(rawUtterance)
        val wakeMatch = WAKE_WORD_PATTERN.find(normalized) ?: return null
        return (normalized.substring(0, wakeMatch.range.first) + " " + normalized.substring(wakeMatch.range.last + 1))
            .trim().replace(Regex("\\s+"), " ")
    }

    /** Exige a palavra de ativação na própria frase. Uso normal — cada turno se justifica sozinho. */
    fun parse(rawUtterance: String): ParsedVoiceCommand? {
        val cleaned = remainderAfterWakeWord(rawUtterance) ?: return null
        return parseCommandOnly(cleaned, alreadyNormalized = true)
    }

    /**
     * Tenta os padrões de comando sem exigir a palavra de ativação na frase — usado só na
     * janela de graça logo depois de um turno que disse a palavra sozinha (ver
     * [VoiceCommandEngine]), quando a pessoa pausou entre o nome e o pedido.
     */
    fun parseCommandOnly(rawUtterance: String, alreadyNormalized: Boolean = false): ParsedVoiceCommand? {
        var cleaned = if (alreadyNormalized) rawUtterance else normalize(rawUtterance)

        val hasOverride = VoiceGrammar.ACCOUNT_OVERRIDE_PHRASES.any { cleaned.contains(it) }
        if (hasOverride) {
            VoiceGrammar.ACCOUNT_OVERRIDE_PHRASES.forEach { cleaned = cleaned.replace(it, " ") }
            cleaned = cleaned.trim().replace(Regex("\\s+"), " ")
        }
        val accountOverride = if (hasOverride) Prefs.PKG_BUSINESS else null

        READ_LAST_PATTERN.find(cleaned)?.let { m ->
            val gap = m.groupValues[1]
            val count = Regex("""\d+""").find(gap)?.value?.toIntOrNull()?.coerceIn(1, VoiceGrammar.MAX_READ_COUNT)
                ?: wordCount(gap)
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

    /** Número por extenso ("dez", "décimo", ...) dentro do trecho entre o verbo e "mensagens". */
    private fun wordCount(gap: String): Int? =
        VoiceGrammar.NUMBER_WORDS.entries
            .filter { (_, n) -> n in 1..VoiceGrammar.MAX_READ_COUNT }
            .firstOrNull { (word, _) -> Regex("\\b$word\\b").containsMatchIn(gap) }
            ?.value

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
