package br.com.wanotifkeeper

/**
 * Fronteira de persistência das mensagens armadas.
 *
 * Existe para que a máquina de estados de [ScheduledMessageCoordinator] possa ser
 * exercitada em teste unitário JVM puro — este projeto não tem Robolectric nem
 * `room-testing`, e a lógica que mais precisa de prova (claim atômico, entrega
 * única, backoff) não depende de Android nenhum.
 */
interface ScheduledMessageStore {

    /** Mensagem candidata ao disparo desta conversa, ou `null` se não há nada a fazer. */
    suspend fun nextEligible(packageName: String, sender: String, now: Long): ScheduledMessageEntity?

    /**
     * Tenta tomar posse. `true` só para **uma** execução — a segunda chamada
     * concorrente sobre a mesma linha devolve `false` e não pode enviar nada.
     */
    suspend fun claim(id: Long, now: Long, triggerKey: String?): Boolean

    suspend fun markSent(id: Long, now: Long)

    /** Falha ainda recuperável: volta a `PENDING`, só elegível de novo a partir de [retryAt]. */
    suspend fun markRetryable(id: Long, now: Long, error: String, retryAt: Long)

    /** Falha terminal: `FAILED`, visível, nunca convertida em sucesso. */
    suspend fun markFailed(id: Long, now: Long, error: String)

    suspend fun byId(id: Long): ScheduledMessageEntity?
}

/** Implementação de produção: delega ao Room, onde o claim é um `UPDATE` condicional. */
class RoomScheduledMessageStore(private val dao: ScheduledMessageDao) : ScheduledMessageStore {

    override suspend fun nextEligible(packageName: String, sender: String, now: Long) =
        dao.nextEligible(packageName, sender, now)

    override suspend fun claim(id: Long, now: Long, triggerKey: String?): Boolean =
        dao.claim(id, now, triggerKey) == 1

    override suspend fun markSent(id: Long, now: Long) { dao.markSent(id, now) }

    override suspend fun markRetryable(id: Long, now: Long, error: String, retryAt: Long) {
        dao.markRetryable(id, now, error, retryAt)
    }

    override suspend fun markFailed(id: Long, now: Long, error: String) {
        dao.markFailed(id, now, error)
    }

    override suspend fun byId(id: Long) = dao.byId(id)
}
