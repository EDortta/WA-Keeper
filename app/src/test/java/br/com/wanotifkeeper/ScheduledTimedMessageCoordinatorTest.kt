package br.com.wanotifkeeper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTimedMessageCoordinatorTest {

    private class OneRowStore(var row: ScheduledMessageEntity) : ScheduledMessageStore {
        override suspend fun nextEligible(packageName: String, sender: String, now: Long) = null

        override suspend fun claim(id: Long, now: Long, triggerKey: String?): Boolean {
            if (row.id != id || row.scheduledState != ScheduledState.PENDING) return false
            row = row.copy(
                state = ScheduledState.CLAIMED.name,
                claimedAt = now,
                updatedAt = now,
                attempts = row.attempts + 1,
                triggerNotificationKey = triggerKey,
                triggeredAt = now
            )
            return true
        }

        override suspend fun markSent(id: Long, now: Long): Boolean {
            if (row.id != id || row.scheduledState != ScheduledState.CLAIMED) return false
            row = row.copy(state = ScheduledState.SENT.name, sentAt = now, updatedAt = now)
            return true
        }

        override suspend fun markRetryable(
            id: Long,
            now: Long,
            error: String,
            retryAt: Long,
            consumesAttempt: Boolean
        ): Boolean {
            if (row.id != id || row.scheduledState != ScheduledState.CLAIMED) return false
            row = row.copy(
                state = ScheduledState.PENDING.name,
                updatedAt = now,
                lastError = error,
                nextAttemptAt = retryAt,
                attempts = if (consumesAttempt) row.attempts else row.attempts - 1
            )
            return true
        }

        override suspend fun markFailed(id: Long, now: Long, error: String): Boolean {
            if (row.id != id || row.scheduledState != ScheduledState.CLAIMED) return false
            row = row.copy(state = ScheduledState.FAILED.name, updatedAt = now, lastError = error)
            return true
        }

        override suspend fun failStaleClaims(now: Long, staleBefore: Long) = 0
        override suspend fun byId(id: Long) = row.takeIf { it.id == id }
    }

    private class MediaAwareSender : ReplySender {
        var textCalls = 0
        var mediaCalls = 0
        var lastUri: String? = null
        var lastMime: String? = null

        override suspend fun send(packageName: String, sender: String, text: String): ReplyResult {
            textCalls++
            return ReplyResult.Accepted
        }

        override suspend fun sendMedia(
            packageName: String,
            sender: String,
            text: String,
            uri: String,
            mimeType: String
        ): ReplyResult {
            mediaCalls++
            lastUri = uri
            lastMime = mimeType
            return ReplyResult.Accepted
        }
    }

    private fun timed(
        scheduledAt: Long,
        mediaUri: String? = null,
        mediaMime: String? = null
    ) = ScheduledMessageEntity(
        id = 7L,
        packageName = "com.whatsapp",
        sender = "Ana",
        text = if (mediaUri == null) "mensagem" else "legenda",
        triggerType = ScheduledTrigger.AT_TIME.name,
        scheduledAt = scheduledAt,
        mediaUri = mediaUri,
        mediaMimeType = mediaMime,
        mediaName = if (mediaUri == null) null else "arquivo.pdf",
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun `time trigger before scheduled instant does nothing`() = runBlocking {
        val store = OneRowStore(timed(scheduledAt = 2_000L))
        val sender = MediaAwareSender()
        val coordinator = ScheduledMessageCoordinator(store, sender, clock = { 1_000L })

        val outcome = coordinator.onTimedMessage(7L)

        assertEquals(TriggerOutcome.NothingArmed, outcome)
        assertEquals(ScheduledState.PENDING, store.row.scheduledState)
        assertEquals(0, sender.textCalls)
    }

    @Test
    fun `time trigger at scheduled instant sends once`() = runBlocking {
        val store = OneRowStore(timed(scheduledAt = 1_000L))
        val sender = MediaAwareSender()
        val coordinator = ScheduledMessageCoordinator(store, sender, clock = { 1_000L })

        val outcome = coordinator.onTimedMessage(7L)

        assertEquals(TriggerOutcome.Sent(7L), outcome)
        assertEquals(ScheduledState.SENT, store.row.scheduledState)
        assertEquals(1, sender.textCalls)
    }

    @Test
    fun `media row uses media channel instead of text channel`() = runBlocking {
        val store = OneRowStore(
            timed(
                scheduledAt = 1_000L,
                mediaUri = "content://docs/42",
                mediaMime = "application/pdf"
            )
        )
        val sender = MediaAwareSender()
        val coordinator = ScheduledMessageCoordinator(store, sender, clock = { 1_000L })

        val outcome = coordinator.onTimedMessage(7L)

        assertEquals(TriggerOutcome.Sent(7L), outcome)
        assertEquals(0, sender.textCalls)
        assertEquals(1, sender.mediaCalls)
        assertEquals("content://docs/42", sender.lastUri)
        assertEquals("application/pdf", sender.lastMime)
    }

    @Test
    fun `backoff also protects timed trigger`() = runBlocking {
        val store = OneRowStore(timed(scheduledAt = 1_000L).copy(nextAttemptAt = 5_000L))
        val sender = MediaAwareSender()
        val coordinator = ScheduledMessageCoordinator(store, sender, clock = { 2_000L })

        val outcome = coordinator.onTimedMessage(7L)

        assertEquals(TriggerOutcome.NothingArmed, outcome)
        assertTrue(sender.textCalls == 0 && sender.mediaCalls == 0)
    }
}
