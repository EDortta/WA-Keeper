package br.com.wanotifkeeper

import java.text.Normalizer

/**
 * Casa o nome falado (transcrito, com erros de reconhecimento) contra os remetentes já
 * vistos no banco. Sem biblioteca nova: normaliza (minúsculas, sem acento) e tenta, em
 * ordem, casamento exato → substring → distância de edição — parando no primeiro nível
 * que resolve para um único candidato.
 */
object VoiceSenderMatcher {

    sealed class MatchResult {
        data class Confident(val sender: String) : MatchResult()
        data class Ambiguous(val candidates: List<String>) : MatchResult()
        object NoMatch : MatchResult()
    }

    private const val MAX_CANDIDATES = 5

    fun match(rawTarget: String, senders: List<String>): MatchResult {
        val target = normalize(rawTarget)
        if (senders.isEmpty() || target.isBlank()) return MatchResult.NoMatch

        val bySender = senders.associateBy { normalize(it) }

        bySender[target]?.let { return MatchResult.Confident(it) }

        val substringMatches = bySender.filterKeys { it.contains(target) || target.contains(it) }
        when (substringMatches.size) {
            1 -> return MatchResult.Confident(substringMatches.values.first())
            in 2..Int.MAX_VALUE -> return MatchResult.Ambiguous(
                substringMatches.values.sortedBy { it.length }.take(MAX_CANDIDATES)
            )
        }

        val scored = bySender.entries
            .map { (norm, original) -> original to levenshtein(norm, target) }
            .sortedBy { it.second }
        val threshold = maxOf(1, target.length / 4)
        val close = scored.filter { it.second <= threshold }

        return when {
            close.isEmpty() -> MatchResult.NoMatch
            close.size == 1 -> MatchResult.Confident(close.first().first)
            else -> {
                val best = close[0].second
                val runnerUp = close.getOrNull(1)?.second ?: Int.MAX_VALUE
                // Folga grande o bastante entre 1º e 2º colocado: aceita sem perguntar.
                if (runnerUp - best >= 2) MatchResult.Confident(close.first().first)
                else MatchResult.Ambiguous(close.take(MAX_CANDIDATES).map { it.first })
            }
        }
    }

    private fun normalize(s: String): String {
        val stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        return stripped.lowercase().trim().replace(Regex("\\s+"), " ")
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }
}
