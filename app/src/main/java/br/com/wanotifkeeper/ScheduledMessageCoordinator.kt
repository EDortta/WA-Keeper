package br.com.wanotifkeeper

sealed class TriggerOutcome {
    object OwnMessage : TriggerOutcome()
    object NothingArmed : TriggerOutcome()
    object LostClaim : TriggerOutcome()
    data class Sent(val id: Long) : TriggerOutcome()
    data class SentNotRecorded(val id: Long) : TriggerOutcome()
    object Vanished : TriggerOutcome()
    object EchoWindow : TriggerOutcome()
    data class Retrying(val id: Long, val attempt: Int, val reason: String) : TriggerOutcome()
    data class Failed(val id: Long, val reason: String) : TriggerOutcome()
}

/**
 * Máquina de estados das mensagens programadas. Continua sem dependência de Android:
 * gatilho por conversa e gatilho por relógio convergem na mesma posse atômica e no mesmo envio.
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
        val key = "$packageName|$conversationSender"
        val since = at - (lastDelivery[key] ?: Long.MIN_VALUE / 2)
        if (since in 0 until echoWindowMs) {
            log("gatilho de $key ignorado: ${since}ms depois da nossa própria entrega")
            return TriggerOutcome.EchoWindow
        }

        sweepStaleClaims(at)

        val candidate = store.nextEligible(packageName, conversationSender, at)
            ?: return TriggerOutcome.NothingArmed

        return deliver(candidate, at, triggerNotificationKey)
    }

    /**
     * Disparo por relógio. O caller já encontrou uma linha vencida; ainda assim revalidamos
     * estado, tipo, horário e backoff antes do claim para que alarmes repetidos sejam inócuos.
     */
    suspend fun onTimedMessage(id: Long): TriggerOutcome {
        val at = clock()
        sweepStaleClaims(at)

        val candidate = store.byId(id) ?: return TriggerOutcome.Vanished
        if (candidate.scheduledState != ScheduledState.PENDING ||
            candidate.scheduledTrigger != ScheduledTrigger.AT_TIME ||
            candidate.scheduledAt == null || candidate.scheduledAt > at ||
            candidate.nextAttemptAt > at
        ) {
            return TriggerOutcome.NothingArmed
        }

        return deliver(candidate, at, "time:${candidate.scheduledAt}")
    }

    private suspend fun sweepStaleClaims(at: Long) {
        if (at - lastStaleSweepAt < staleClaimMs) return
        lastStaleSweepAt = at
        val encerradas = store.failStaleClaims(at, at - staleClaimMs)
        if (encerradas > 0) log("$encerradas claim(s) preso(s) encerrado(s) como FAILED")
    }

    private suspend fun deliver(
        candidate: ScheduledMessageEntity,
        at: Long,
        triggerKey: String?
    ): TriggerOutcome {
        if (!store.claim(candidate.id, at, triggerKey)) {
            log("claim perdido para #${candidate.id} — gatilho duplicado não envia")
            return TriggerOutcome.LostClaim
        }

        val row = store.byId(candidate.id) ?: return TriggerOutcome.Vanished
        if (row.scheduledState != ScheduledState.CLAIMED) {
            log("#${candidate.id} saiu de CLAIMED antes do envio (${row.scheduledState}) — não envia")
            return TriggerOutcome.LostClaim
        }

        val attempt = row.attempts
        val result = runCatching {
            if (row.hasMedia) {
                sender.sendMedia(
                    packageName = row.packageName,
                    sender = row.sender,
                    text = row.text,
                    uri = row.mediaUri!!,
                    mimeType = row.mediaMimeType!!
                )
            } else {
                sender.send(row.packageName, row.sender, row.text)
            }
        }.getOrElse { e ->
            ReplyResult.Rejected("${e.javaClass.simpleName}: ${e.message ?: "sem detalhe"}")
        }

        return when (result) {
            is ReplyResult.Accepted -> {
                if (!store.markSent(candidate.id, clock())) {
                    log("#${candidate.id} DESPACHADO mas o carimbo de SENT não pegou — linha saiu de CLAIMED")
                    return TriggerOutcome.SentNotRecorded(candidate.id)
                }
                lastDelivery["${row.packageName}|${row.sender}"] = clock()
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
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_RETRY_BACKOFF_MS = 60_000L
        const val DEFAULT_STALE_CLAIM_MS = 5 * 60_000L
        const val DEFAULT_ECHO_WINDOW_MS = 20_000L
    }
}
