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

    /** A linha sumiu entre o claim e a releitura. Nada foi enviado. */
    object Vanished : TriggerOutcome()

    /**
     * Chegou logo depois de uma entrega nossa para esta mesma conversa. É quase
     * certamente o repost que o WhatsApp faz da própria notificação com a resposta
     * anexada — e a defesa aqui não depende do formato da notificação, só do relógio.
     */
    object EchoWindow : TriggerOutcome()

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
    private val echoWindowMs: Long = DEFAULT_ECHO_WINDOW_MS,
    private val log: (String) -> Unit = {}
) {

    /**
     * Chamado quando uma notificação de [packageName]/[conversationSender] chega.
     *
     * [fromSelf] vem da detecção de mensagem do próprio usuário: eco da nossa própria
     * resposta não pode virar gatilho, senão a entrega se realimenta.
     */
    /** Última entrega aceita por conversa. Só memória: perder isto no reinício é inócuo. */
    private val lastDelivery = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile private var lastStaleSweepAt = 0L

    suspend fun onConversationActivity(
        packageName: String,
        conversationSender: String,
        fromSelf: Boolean,
        triggerNotificationKey: String?
    ): TriggerOutcome {
        if (fromSelf) return TriggerOutcome.OwnMessage

        val at = clock()

        // Defesa determinística contra a realimentação: logo depois de entregarmos algo
        // a esta conversa, o WhatsApp reposta a notificação com a resposta anexada. Não
        // dá para distinguir isso pelo conteúdo de forma confiável (ver OwnMessageHeuristic),
        // mas dá pelo relógio — e o relógio não depende do formato da notificação.
        val key = "$packageName|$conversationSender"
        val since = at - (lastDelivery[key] ?: Long.MIN_VALUE / 2)
        if (since in 0 until echoWindowMs) {
            log("gatilho de $key ignorado: ${since}ms depois da nossa própria entrega")
            return TriggerOutcome.EchoWindow
        }

        // Antes de qualquer coisa: encerrar claims presos de um processo que morreu
        // enviando. Roda a cada gatilho de propósito — na conexão do listener, que era
        // o lugar anterior, o caso comum (serviço religado segundos depois) nunca era
        // alcançado, e a linha ficava "Enviando agora…" para sempre.
        // Com throttle: varrer a tabela inteira a cada notificação do WhatsApp custaria
        // uma transação de escrita por mensagem recebida, e o índice da tabela é por
        // (pacote, remetente, estado) — o predicado desta varredura não o usa.
        if (at - lastStaleSweepAt >= staleClaimMs) {
            lastStaleSweepAt = at
            val encerradas = store.failStaleClaims(at, at - staleClaimMs)
            if (encerradas > 0) log("$encerradas claim(s) preso(s) encerrado(s) como FAILED")
        }

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
        if (row.scheduledState != ScheduledState.CLAIMED) {
            // A linha saiu de CLAIMED entre o claim e a releitura. Enviar assim mesmo
            // seria despachar sobre uma linha que outro caminho já encerrou.
            log("#${candidate.id} saiu de CLAIMED antes do envio (${row.scheduledState}) — não envia")
            return TriggerOutcome.LostClaim
        }

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
                lastDelivery[key] = clock()
                log("#${candidate.id} despacho aceito pelo Android na tentativa $attempt")
                TriggerOutcome.Sent(candidate.id)
            }
            is ReplyResult.Rejected -> {
                if (result.consumesAttempt && attempt >= maxAttempts) {
                    if (!store.markFailed(candidate.id, clock(), result.reason)) {
                        log("#${candidate.id} não pôde ser marcado FAILED — linha saiu de CLAIMED")
                    }
                    log("#${candidate.id} FALHOU em definitivo após $attempt tentativas: ${result.reason}")
                    TriggerOutcome.Failed(candidate.id, result.reason)
                } else {
                    val retryAt = clock() + retryBackoffMs
                    if (!store.markRetryable(
                            candidate.id, clock(), result.reason, retryAt, result.consumesAttempt
                        )
                    ) {
                        log("#${candidate.id} não pôde voltar para PENDING — linha saiu de CLAIMED")
                    }
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

        /** Janela em que uma notificação da mesma conversa é tratada como eco da entrega. */
        const val DEFAULT_ECHO_WINDOW_MS = 20_000L
    }
}
