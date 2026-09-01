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

    /**
     * `false` quando o `UPDATE` não achou a linha em `CLAIMED` — ou seja, alguém
     * mexeu nela por baixo. Devolver `Unit` aqui era um *lost update* silencioso: o
     * envio acontecia, o carimbo não pegava, e a linha voltava a ser elegível.
     */
    suspend fun markSent(id: Long, now: Long): Boolean

    /**
     * Falha ainda recuperável: volta a `PENDING`, só elegível de novo a partir de
     * [retryAt]. [consumesAttempt] `false` devolve a tentativa — ver
     * `markRetryableWithoutConsumingAttempt`.
     */
    suspend fun markRetryable(
        id: Long, now: Long, error: String, retryAt: Long, consumesAttempt: Boolean
    ): Boolean

    /** Falha terminal: `FAILED`, visível, nunca convertida em sucesso. */
    suspend fun markFailed(id: Long, now: Long, error: String): Boolean

    /**
     * Encerra como `FAILED` os claims presos de um processo que morreu enviando.
     * Chamado a cada gatilho: rodar só na conexão do listener nunca pegava o caso
     * comum, em que o serviço religa segundos depois e a linha ainda é "nova".
     */
    suspend fun failStaleClaims(now: Long, staleBefore: Long): Int

    suspend fun byId(id: Long): ScheduledMessageEntity?
}

/** Implementação de produção: delega ao Room, onde o claim é um `UPDATE` condicional. */
class RoomScheduledMessageStore(private val dao: ScheduledMessageDao) : ScheduledMessageStore {

    override suspend fun nextEligible(packageName: String, sender: String, now: Long) =
        dao.nextEligible(packageName, sender, now)

    override suspend fun claim(id: Long, now: Long, triggerKey: String?): Boolean =
        dao.claim(id, now, triggerKey) == 1

    override suspend fun markSent(id: Long, now: Long): Boolean = dao.markSent(id, now) == 1

    override suspend fun markRetryable(
        id: Long, now: Long, error: String, retryAt: Long, consumesAttempt: Boolean
    ): Boolean = if (consumesAttempt) {
        dao.markRetryable(id, now, error, retryAt) == 1
    } else {
        dao.markRetryableWithoutConsumingAttempt(id, now, error, retryAt) == 1
    }

    override suspend fun markFailed(id: Long, now: Long, error: String): Boolean =
        dao.markFailed(id, now, error) == 1

    override suspend fun failStaleClaims(now: Long, staleBefore: Long): Int =
        dao.failStaleClaims(now, staleBefore)

    override suspend fun byId(id: Long) = dao.byId(id)
}
