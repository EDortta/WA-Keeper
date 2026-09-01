package br.com.wanotifkeeper

/**
 * O que aconteceu com um gatilho. Existe para que o teste (e o log) leiam a decisão
 * em vez de inferi-la do estado do banco depois.
 */
sealed class TriggerOutcome {
    /** A mensagem era do próprio usuário — a #18 diz que isso não é gatilho. */
    object OwnMessage : TriggerOutcome()

    /** Nada armado para esta conversa, ou o que havia ainda está de castigo pelo backoff. */
    object NothingArmed : TriggerOutcome()

    /**
     * Outra execução levou o claim. Este é o caminho da notificação repetida,
     * agrupada ou atualizada: chega junto, perde a corrida, e **não envia nada**.
     */
    object LostClaim : TriggerOutcome()

    /** O mecanismo de envio aceitou o despacho. Só aqui a linha vira `SENT`. */
    data class Sent(val id: Long) : TriggerOutcome()

    /**
     * O envio foi despachado, mas o carimbo de `SENT` não pegou — a linha saiu de
     * `CLAIMED` por baixo. Estado ruim e raro, que precisa aparecer no log em vez de
     * se disfarçar de sucesso.
     */
    data class SentNotRecorded(val id: Long) : TriggerOutcome()

    /** A linha sumiu entre a leitura e o claim. Nada foi enviado. */
    object Vanished : TriggerOutcome()

    /** Falhou, mas continua recuperável: volta a `PENDING`, com espera antes do próximo gatilho. */
    data class Retrying(val id: Long, val attempt: Int, val reason: String) : TriggerOutcome()

    /** Tentativas esgotadas: `FAILED`, terminal e visível. Nunca convertido em sucesso. */
    data class Failed(val id: Long, val reason: String) : TriggerOutcome()
}

/**
 * A máquina de estados da EPIC 4 (#18), sem uma linha de Android — é o que dá para
 * provar nesta janela, e é onde mora o risco de verdade.
 *
 * Um gatilho = uma notificação nova daquela conversa = **no máximo uma** tentativa de
 * entrega, de **uma** mensagem armada. Nunca reprograma nada sozinho.
 */
class ScheduledMessageCoordinator(
    private val store: ScheduledMessageStore,
    private val sender: ReplySender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryBackoffMs: Long = DEFAULT_RETRY_BACKOFF_MS,
    private val staleClaimMs: Long = DEFAULT_STALE_CLAIM_MS,
    private val log: (String) -> Unit = {}
) {

    /**
     * Chamado quando uma notificação de [packageName]/[conversationSender] chega.
     *
     * [fromSelf] vem da detecção de mensagem do próprio usuário: eco da nossa própria
     * resposta não pode virar gatilho, senão a entrega se realimenta.
     */
    suspend fun onConversationActivity(
        packageName: String,
        conversationSender: String,
        fromSelf: Boolean,
        triggerNotificationKey: String?
    ): TriggerOutcome {
        if (fromSelf) return TriggerOutcome.OwnMessage

        val at = clock()

        // Antes de qualquer coisa: encerrar claims presos de um processo que morreu
        // enviando. Roda a cada gatilho de propósito — na conexão do listener, que era
        // o lugar anterior, o caso comum (serviço religado segundos depois) nunca era
        // alcançado, e a linha ficava "Enviando agora…" para sempre.
        store.failStaleClaims(at, at - staleClaimMs)

        val candidate = store.nextEligible(packageName, conversationSender, at)
            ?: return TriggerOutcome.NothingArmed

        // A porta estreita: só uma execução atravessa. Tudo antes disto é leitura,
        // tudo depois já é posse exclusiva da linha.
        if (!store.claim(candidate.id, at, triggerNotificationKey)) {
            log("claim perdido para #${candidate.id} — notificação duplicada não envia")
            return TriggerOutcome.LostClaim
        }

        // Releitura obrigatória depois do claim: o claim protege a posse da linha, não
        // o conteúdo. Uma edição que caísse entre nextEligible e claim faria sair o
        // texto velho enquanto a tela mostrasse o novo.
        val row = store.byId(candidate.id) ?: return TriggerOutcome.Vanished

        val attempt = row.attempts
        val result = runCatching {
            sender.send(packageName, conversationSender, row.text)
        }.getOrElse { e ->
            // Sem isto, uma exceção inesperada deixaria a linha presa em CLAIMED e a
            // mensagem nunca mais sairia (só releaseStaleClaims a resgataria).
            ReplyResult.Rejected("${e.javaClass.simpleName}: ${e.message ?: "sem detalhe"}")
        }

        return when (result) {
            is ReplyResult.Accepted -> {
                if (!store.markSent(candidate.id, clock())) {
                    log("#${candidate.id} DESPACHADO mas o carimbo de SENT não pegou — linha saiu de CLAIMED")
                    return TriggerOutcome.SentNotRecorded(candidate.id)
                }
                log("#${candidate.id} despacho aceito pelo Android na tentativa $attempt")
                TriggerOutcome.Sent(candidate.id)
            }
            is ReplyResult.Rejected -> {
                if (result.consumesAttempt && attempt >= maxAttempts) {
                    store.markFailed(candidate.id, clock(), result.reason)
                    log("#${candidate.id} FALHOU em definitivo após $attempt tentativas: ${result.reason}")
                    TriggerOutcome.Failed(candidate.id, result.reason)
                } else {
                    val retryAt = clock() + retryBackoffMs
                    store.markRetryable(
                        candidate.id, clock(), result.reason, retryAt, result.consumesAttempt
                    )
                    log("#${candidate.id} falhou na tentativa $attempt (${result.reason}) — nova chance após $retryAt")
                    TriggerOutcome.Retrying(candidate.id, attempt, result.reason)
                }
            }
        }
    }

    companion object {
        /**
         * Três disparos. Depois disso a linha vira `FAILED` com o motivo guardado —
         * é assim que "esta versão do WhatsApp não expõe resposta direta" fica
         * **registrado** em vez de virar tentativa eterna.
         */
        const val DEFAULT_MAX_ATTEMPTS = 3

        /**
         * O gatilho é uma notificação, não um timer — mas uma rajada de notificações
         * seria um loop apertado por tabela. Um minuto de espera entre tentativas
         * corta isso sem transformar a entrega em algo lento.
         */
        const val DEFAULT_RETRY_BACKOFF_MS = 60_000L

        /** Claim mais velho que isto é resto de processo morto, não envio em andamento. */
        const val DEFAULT_STALE_CLAIM_MS = 5 * 60_000L
    }
}
