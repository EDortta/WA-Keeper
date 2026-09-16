package br.com.wanotifkeeper

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class ScheduledState { PENDING, CLAIMED, SENT, FAILED, CANCELLED }
enum class ScheduledTrigger { NEXT_INCOMING, AT_TIME }

const val STALE_CLAIM_REASON =
    "o app foi encerrado durante o envio — não é possível saber se a mensagem saiu"

@Entity(
    tableName = "scheduled_messages",
    indices = [
        Index(
            value = ["packageName", "sender", "state"],
            name = "index_scheduled_messages_conversation"
        ),
        Index(
            value = ["triggerType", "state", "scheduledAt"],
            name = "index_scheduled_messages_time"
        )
    ]
)
data class ScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val sender: String,
    val text: String,
    @ColumnInfo(defaultValue = "'NEXT_INCOMING'")
    val triggerType: String = ScheduledTrigger.NEXT_INCOMING.name,
    val scheduledAt: Long? = null,
    val mediaUri: String? = null,
    val mediaMimeType: String? = null,
    val mediaName: String? = null,
    val state: String = ScheduledState.PENDING.name,
    val createdAt: Long,
    val updatedAt: Long,
    val claimedAt: Long? = null,
    val sentAt: Long? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val nextAttemptAt: Long = 0L,
    val triggerNotificationKey: String? = null,
    val triggeredAt: Long? = null
) {
    val scheduledState: ScheduledState
        get() = runCatching { ScheduledState.valueOf(state) }.getOrDefault(ScheduledState.PENDING)

    val scheduledTrigger: ScheduledTrigger
        get() = runCatching { ScheduledTrigger.valueOf(triggerType) }
            .getOrDefault(ScheduledTrigger.NEXT_INCOMING)

    val isEditable: Boolean
        get() = scheduledState == ScheduledState.PENDING

    val hasMedia: Boolean
        get() = !mediaUri.isNullOrBlank() && !mediaMimeType.isNullOrBlank()
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

    @Query(
        "SELECT * FROM scheduled_messages " +
            "WHERE packageName = :pkg AND sender = :sender " +
            "AND triggerType = 'NEXT_INCOMING' " +
            "AND state = 'PENDING' AND nextAttemptAt <= :now " +
            "ORDER BY createdAt ASC LIMIT 1"
    )
    suspend fun nextEligible(pkg: String, sender: String, now: Long): ScheduledMessageEntity?

    @Query(
        "SELECT * FROM scheduled_messages " +
            "WHERE triggerType = 'AT_TIME' AND state = 'PENDING' " +
            "AND scheduledAt IS NOT NULL AND scheduledAt <= :now AND nextAttemptAt <= :now " +
            "ORDER BY scheduledAt ASC, createdAt ASC"
    )
    suspend fun dueTimed(now: Long): List<ScheduledMessageEntity>

    @Query(
        "SELECT * FROM scheduled_messages " +
            "WHERE packageName = :pkg AND sender = :sender " +
            "AND triggerType = 'AT_TIME' AND state = 'PENDING' " +
            "AND scheduledAt IS NOT NULL AND scheduledAt <= :now AND nextAttemptAt <= :now " +
            "ORDER BY scheduledAt ASC, createdAt ASC"
    )
    suspend fun dueTimedForConversation(pkg: String, sender: String, now: Long): List<ScheduledMessageEntity>

    /**
     * Mensagem parada especificamente por falta de RemoteInput espera passivamente uma nova
     * notificação da conversa; não acorda o telefone a cada minuto sem ter como mudar o cenário.
     */
    @Query(
        "SELECT MIN(CASE " +
            "WHEN nextAttemptAt > scheduledAt THEN nextAttemptAt ELSE scheduledAt END) " +
            "FROM scheduled_messages " +
            "WHERE triggerType = 'AT_TIME' AND state = 'PENDING' AND scheduledAt IS NOT NULL " +
            "AND (lastError IS NULL OR lastError != :waitForConversationError)"
    )
    suspend fun nextTimedAt(waitForConversationError: String): Long?

    @Query(
        "UPDATE scheduled_messages SET " +
            "state = 'CLAIMED', claimedAt = :now, updatedAt = :now, attempts = attempts + 1, " +
            "triggerNotificationKey = :triggerKey, triggeredAt = :now " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun claim(id: Long, now: Long, triggerKey: String?): Int

    @Query(
        "UPDATE scheduled_messages SET state = 'SENT', sentAt = :now, updatedAt = :now, " +
            "lastError = NULL WHERE id = :id AND state = 'CLAIMED'"
    )
    suspend fun markSent(id: Long, now: Long): Int

    @Query(
        "UPDATE scheduled_messages SET state = 'PENDING', updatedAt = :now, " +
            "lastError = :error, nextAttemptAt = :retryAt WHERE id = :id AND state = 'CLAIMED'"
    )
    suspend fun markRetryable(id: Long, now: Long, error: String, retryAt: Long): Int

    @Query(
        "UPDATE scheduled_messages SET state = 'PENDING', updatedAt = :now, " +
            "lastError = :error, nextAttemptAt = :retryAt, attempts = attempts - 1 " +
            "WHERE id = :id AND state = 'CLAIMED'"
    )
    suspend fun markRetryableWithoutConsumingAttempt(id: Long, now: Long, error: String, retryAt: Long): Int

    @Query(
        "UPDATE scheduled_messages SET state = 'FAILED', updatedAt = :now, " +
            "lastError = :error WHERE id = :id AND state = 'CLAIMED'"
    )
    suspend fun markFailed(id: Long, now: Long, error: String): Int

    @Query(
        "UPDATE scheduled_messages SET state = 'CANCELLED', updatedAt = :now " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun cancel(id: Long, now: Long): Int

    @Query(
        "UPDATE scheduled_messages SET text = :text, updatedAt = :now " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun updateText(id: Long, text: String, now: Long): Int

    @Query(
        "UPDATE scheduled_messages SET state = 'FAILED', updatedAt = :now, " +
            "lastError = '" + STALE_CLAIM_REASON + "' " +
            "WHERE state = 'CLAIMED' AND claimedAt < :staleBefore"
    )
    suspend fun failStaleClaims(now: Long, staleBefore: Long): Int

    @Query("DELETE FROM scheduled_messages WHERE id = :id AND state != 'CLAIMED'")
    suspend fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE state = 'PENDING'")
    suspend fun pendingCount(): Int
}
