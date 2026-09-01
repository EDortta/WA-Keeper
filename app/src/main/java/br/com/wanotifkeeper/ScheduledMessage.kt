package br.com.wanotifkeeper

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Ciclo de vida de uma mensagem armada (EPIC 4 / #18).
 *
 * `PENDING -> CLAIMED -> SENT` é o caminho feliz. `CLAIMED` existe para que a
 * transição de posse seja atômica: duas notificações da mesma conversa chegando
 * juntas disputam o mesmo `UPDATE ... WHERE state='PENDING'`, e só uma leva.
 *
 * Falha **não** é terminal na primeira vez: volta a `PENDING` com a tentativa
 * registrada e um `nextAttemptAt` no futuro, para que uma rajada de notificações
 * não vire loop apertado de reenvio. `FAILED` só depois de esgotar as tentativas —
 * aí é terminal e visível, sem nunca alegar envio.
 */
enum class ScheduledState { PENDING, CLAIMED, SENT, FAILED, CANCELLED }

/**
 * Uma mensagem armada para uma conversa, entregue **uma única vez** quando aquela
 * conversa mandar a próxima mensagem.
 *
 * A identidade da conversa é `packageName + sender`: o mesmo nome de exibição no
 * WhatsApp e no WhatsApp Business são conversas diferentes, e a #18 exige que as
 * duas contas funcionem separadamente.
 */
// O índice não é enfeite: toda notificação do WhatsApp consulta por
// (pacote, remetente, estado), e o nome precisa bater com o da MIGRATION_4_5 —
// senão o Room recusa o banco migrado por divergência de schema.
@Entity(
    tableName = "scheduled_messages",
    indices = [Index(
        value = ["packageName", "sender", "state"],
        name = "index_scheduled_messages_conversation"
    )]
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val sender: String,
    val text: String,
    val state: String = ScheduledState.PENDING.name,
    val createdAt: Long,
    val updatedAt: Long,
    /** Quando o claim mais recente aconteceu — auditoria de "quem pegou e quando". */
    val claimedAt: Long? = null,
    /** Só preenchido quando o mecanismo de envio aceitou. Nunca antes. */
    val sentAt: Long? = null,
    /** Nº de disparos já tentados. Cresce no claim, não no sucesso. */
    val attempts: Int = 0,
    /** Motivo da última falha, textual, para a UI mostrar sem inventar. */
    val lastError: String? = null,
    /** Antes disto, um novo gatilho é ignorado — é o freio contra loop apertado. */
    val nextAttemptAt: Long = 0L,
    /** `StatusBarNotification.key` da notificação que disparou a entrega. */
    val triggerNotificationKey: String? = null,
    val triggeredAt: Long? = null
) {
    val scheduledState: ScheduledState
        get() = runCatching { ScheduledState.valueOf(state) }.getOrDefault(ScheduledState.PENDING)

    /** Ainda dá para editar/cancelar: nada foi entregue nem está em voo. */
    val isEditable: Boolean
        get() = scheduledState == ScheduledState.PENDING
}

@Dao
interface ScheduledMessageDao {

    @Insert
    suspend fun insert(msg: ScheduledMessageEntity): Long

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun byId(id: Long): ScheduledMessageEntity?

    @Query("SELECT * FROM scheduled_messages ORDER BY createdAt DESC")
    fun allFlow(): Flow<List<ScheduledMessageEntity>>

    @Query(
        "SELECT * FROM scheduled_messages " +
            "WHERE packageName = :pkg AND sender = :sender ORDER BY createdAt DESC"
    )
    fun forConversationFlow(pkg: String, sender: String): Flow<List<ScheduledMessageEntity>>

    /**
     * Candidata ao próximo disparo desta conversa: a mais antiga que está `PENDING`
     * e cuja janela de espera já passou. Mais antiga primeiro para que armar duas
     * mensagens entregue na ordem em que foram armadas.
     */
    @Query(
        "SELECT * FROM scheduled_messages " +
            "WHERE packageName = :pkg AND sender = :sender " +
            "AND state = 'PENDING' AND nextAttemptAt <= :now " +
            "ORDER BY createdAt ASC LIMIT 1"
    )
    suspend fun nextEligible(pkg: String, sender: String, now: Long): ScheduledMessageEntity?

    /**
     * **O claim atômico.** `WHERE state = 'PENDING'` dentro do próprio `UPDATE` é o
     * que garante que só uma execução leva a mensagem: SQLite serializa a escrita, e
     * a segunda notificação encontra a linha já em `CLAIMED` e recebe 0 linhas.
     *
     * Retorna o número de linhas afetadas — `1` significa "é sua", `0` significa
     * "outro já pegou, não envie".
     */
    @Query(
        "UPDATE scheduled_messages SET " +
            "state = 'CLAIMED', claimedAt = :now, updatedAt = :now, attempts = attempts + 1, " +
            "triggerNotificationKey = :triggerKey, triggeredAt = :now " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun claim(id: Long, now: Long, triggerKey: String?): Int

    /** Só depois de o mecanismo de envio aceitar. */
    @Query(
        "UPDATE scheduled_messages SET state = 'SENT', sentAt = :now, updatedAt = :now, " +
            "lastError = NULL WHERE id = :id AND state = 'CLAIMED'"
    )
    suspend fun markSent(id: Long, now: Long): Int

    /** Falha recuperável: volta a `PENDING`, com o erro guardado e uma espera antes do próximo gatilho. */
    @Query(
        "UPDATE scheduled_messages SET state = 'PENDING', updatedAt = :now, " +
            "lastError = :error, nextAttemptAt = :retryAt WHERE id = :id AND state = 'CLAIMED'"
    )
    suspend fun markRetryable(id: Long, now: Long, error: String, retryAt: Long): Int

    /** Tentativas esgotadas: terminal, visível, e sem jamais alegar envio. */
    @Query(
        "UPDATE scheduled_messages SET state = 'FAILED', updatedAt = :now, " +
            "lastError = :error WHERE id = :id AND state = 'CLAIMED'"
    )
    suspend fun markFailed(id: Long, now: Long, error: String): Int

    /** Desarmar. Só faz sentido enquanto ainda está `PENDING` — em voo não se cancela. */
    @Query(
        "UPDATE scheduled_messages SET state = 'CANCELLED', updatedAt = :now " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun cancel(id: Long, now: Long): Int

    /** Editar o texto enquanto pendente. Não reabre nada que já saiu. */
    @Query(
        "UPDATE scheduled_messages SET text = :text, updatedAt = :now " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun updateText(id: Long, text: String, now: Long): Int

    /**
     * Sobreviveu a um crash em `CLAIMED`: ninguém está enviando, mas a linha ficou
     * presa. Devolve a `PENDING` para que o próximo contato possa tentar de novo.
     * Chamado uma vez na conexão do listener, nunca em laço.
     */
    @Query(
        "UPDATE scheduled_messages SET state = 'PENDING', updatedAt = :now, " +
            "lastError = 'processo encerrado durante o envio' " +
            "WHERE state = 'CLAIMED' AND claimedAt < :staleBefore"
    )
    suspend fun releaseStaleClaims(now: Long, staleBefore: Long): Int

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE state = 'PENDING'")
    suspend fun pendingCount(): Int
}
