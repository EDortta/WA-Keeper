package br.com.wanotifkeeper

import kotlinx.coroutines.CompletableDeferred

/**
 * Decide se uma notificação do WhatsApp é mensagem nova, repost do que já foi tratado, ou
 * o repost que enfim traz a imagem. Puro, sem Android, pelo mesmo motivo que [MediaHints] e
 * [VoiceGateDecision] são: é a decisão que precisa de teste.
 *
 * O WhatsApp reposta a mesma conversa várias vezes — ao chegar mensagem nova, ao atualizar a
 * notificação com a imagem baixada, ao publicar o resumo do grupo de notificações. Sem uma
 * identidade estável por MENSAGEM, cada repost vira uma leitura em voz alta a mais e uma
 * linha a mais no banco.
 *
 * Há duas qualidades de identidade, e a diferença importa:
 *
 *  - **Exata**: quando a notificação é `MessagingStyle`, cada mensagem carrega seu próprio
 *    `timestamp`, que não muda entre reposts. `pacote|remetente|timestamp|texto` identifica a
 *    mensagem, não a notificação — então dá para lembrar dela por MINUTOS sem risco de
 *    confundir com uma mensagem nova de texto igual, e o repost tardio (o que traz a imagem,
 *    até 10,5 s depois) continua sendo reconhecido como repost.
 *  - **Aproximada**: sem `MessagingStyle` só resta `pacote|remetente|texto`, que não distingue
 *    a mesma pessoa mandando "ok" duas vezes de propósito. Aí a memória é curta — poucos
 *    segundos —, aceitando de volta o duplicado raro para não engolir mensagem legítima.
 */
class RepostGuard(
    private val exactTtlMs: Long = EXACT_TTL_MS,
    private val approximateTtlMs: Long = APPROXIMATE_TTL_MS
) {

    class Record(
        @Volatile var ts: Long,
        @Volatile var hasImage: Boolean,
        val exact: Boolean,
        /** Preenchido quando o INSERT termina; o repost que traz a imagem espera por ele. */
        val rowId: CompletableDeferred<Long> = CompletableDeferred()
    )

    sealed class Decision {
        /** Mensagem que nunca foi vista: ler, guardar, disparar os ganchos. */
        data class New(val record: Record) : Decision()

        /** Repost sem nada de novo: ignorar por inteiro. */
        object Drop : Decision()

        /**
         * Repost que traz imagem que o primeiro não tinha. O WhatsApp publica o rótulo
         * ("📷 Foto") e só depois atualiza a notificação com o bitmap/URI, com texto idêntico.
         * Descartar isso perdia a única imagem oferecida; inserir de novo duplicava a mensagem
         * na tela. O certo é anexar à linha que já existe.
         */
        data class AttachImage(val record: Record) : Decision()
    }

    private val seen = LinkedHashMap<String, Record>()

    @Synchronized
    fun classify(
        packageName: String,
        sender: String,
        text: String,
        messageTime: Long?,
        hasImageContent: Boolean,
        now: Long
    ): Decision {
        purge(now)

        val exact = messageTime != null && messageTime > 0L
        val key = if (exact) "$packageName|$sender|$messageTime|$text" else "$packageName|$sender|$text"

        val previous = seen[key]
        if (previous == null) {
            val record = Record(now, hasImageContent, exact)
            seen[key] = record
            if (seen.size > MAX_ENTRIES) seen.remove(seen.keys.first())
            return Decision.New(record)
        }

        // `ts` NÃO é atualizado a cada repost: ele marca quando a mensagem foi vista pela
        // primeira vez, e é dele que a validade conta. Renovar a cada repost faria uma rajada
        // de reposts esticar a janela indefinidamente — e, na identidade aproximada, isso
        // engoliria a repetição intencional ("ok" de novo dois segundos depois) para sempre.
        if (hasImageContent && !previous.hasImage) {
            previous.hasImage = true
            return Decision.AttachImage(previous)
        }
        return Decision.Drop
    }

    private fun purge(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val record = it.next().value
            val ttl = if (record.exact) exactTtlMs else approximateTtlMs
            if (now - record.ts > ttl) it.remove()
        }
    }

    companion object {
        /** Identidade exata: longa o bastante para cobrir o repost que traz a imagem. */
        const val EXACT_TTL_MS = 10 * 60_000L

        /** Identidade aproximada: curta, para não engolir repetição intencional. */
        const val APPROXIMATE_TTL_MS = 2_000L

        private const val MAX_ENTRIES = 500
    }
}
