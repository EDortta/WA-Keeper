package br.com.wanotifkeeper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A EPIC 4 (#18) não pode ser verificada em aparelho nesta janela (ver `RESUME.md`
 * da 018). O que **dá** para provar sem aparelho é justamente onde mora o risco: a
 * máquina de estados, a atomicidade do claim e a garantia de entrega única.
 *
 * O fake abaixo reproduz a semântica dos `UPDATE ... WHERE state = ?` do
 * [ScheduledMessageDao] — inclusive o fato de que um update condicional que não casa
 * devolve 0 linhas. Se o DAO mudar de contrato, este fake precisa mudar junto.
 */
class ScheduledMessageCoordinatorTest {

    private class FakeStore : ScheduledMessageStore {
        val rows = linkedMapOf<Long, ScheduledMessageEntity>()
        private var nextId = 1L

        /** Roda logo antes do claim — é assim que a corrida é reproduzida sem thread. */
        var beforeClaim: ((Long) -> Unit)? = null

        fun arm(pkg: String, sender: String, text: String, createdAt: Long = 0L): Long {
            val id = nextId++
            rows[id] = ScheduledMessageEntity(
                id = id, packageName = pkg, sender = sender, text = text,
                createdAt = createdAt, updatedAt = createdAt
            )
            return id
        }

        override suspend fun nextEligible(packageName: String, sender: String, now: Long) =
            rows.values
                .filter {
                    it.packageName == packageName && it.sender == sender &&
                        it.state == ScheduledState.PENDING.name && it.nextAttemptAt <= now
                }
                .minByOrNull { it.createdAt }

        override suspend fun claim(id: Long, now: Long, triggerKey: String?): Boolean {
            beforeClaim?.invoke(id)
            val row = rows[id] ?: return false
            if (row.state != ScheduledState.PENDING.name) return false   // 0 linhas afetadas
            rows[id] = row.copy(
                state = ScheduledState.CLAIMED.name, claimedAt = now, updatedAt = now,
                attempts = row.attempts + 1, triggerNotificationKey = triggerKey, triggeredAt = now
            )
            return true
        }

        /** Claim de fora do coordenador, para simular a execução concorrente que ganhou. */
        fun stealClaim(id: Long) {
            val row = rows[id]!!
            rows[id] = row.copy(state = ScheduledState.CLAIMED.name, attempts = row.attempts + 1)
        }

        override suspend fun markSent(id: Long, now: Long) {
            val row = rows[id] ?: return
            if (row.state != ScheduledState.CLAIMED.name) return
            rows[id] = row.copy(state = ScheduledState.SENT.name, sentAt = now, updatedAt = now, lastError = null)
        }

        override suspend fun markRetryable(id: Long, now: Long, error: String, retryAt: Long) {
            val row = rows[id] ?: return
            if (row.state != ScheduledState.CLAIMED.name) return
            rows[id] = row.copy(
                state = ScheduledState.PENDING.name, updatedAt = now,
                lastError = error, nextAttemptAt = retryAt
            )
        }

        override suspend fun markFailed(id: Long, now: Long, error: String) {
            val row = rows[id] ?: return
            if (row.state != ScheduledState.CLAIMED.name) return
            rows[id] = row.copy(state = ScheduledState.FAILED.name, updatedAt = now, lastError = error)
        }

        override suspend fun byId(id: Long) = rows[id]

        fun cancel(id: Long) {
            val row = rows[id]!!
            rows[id] = row.copy(state = ScheduledState.CANCELLED.name)
        }
    }

    private class RecordingSender(private val result: (String) -> ReplyResult) : ReplySender {
        val sent = mutableListOf<Triple<String, String, String>>()
        override suspend fun send(packageName: String, sender: String, text: String): ReplyResult {
            sent += Triple(packageName, sender, text)
            return result(text)
        }
    }

    private class ThrowingSender : ReplySender {
        var calls = 0
        override suspend fun send(packageName: String, sender: String, text: String): ReplyResult {
            calls++
            throw IllegalStateException("PendingIntent morreu")
        }
    }

    private fun clockAt(vararg times: Long): () -> Long {
        var i = 0
        return { times[minOf(i++, times.size - 1)] }
    }

    private val ok = RecordingSender { ReplyResult.Accepted }

    private fun coordinator(
        store: ScheduledMessageStore,
        sender: ReplySender,
        now: Long = 1_000L,
        maxAttempts: Int = 3
    ) = ScheduledMessageCoordinator(
        store = store, sender = sender, clock = { now },
        maxAttempts = maxAttempts, retryBackoffMs = 60_000L
    )

    // ---- caminho feliz -------------------------------------------------------

    @Test
    fun `mensagem armada e entregue no primeiro contato vira SENT`() = runBlocking {
        val store = FakeStore()
        val id = store.arm("com.whatsapp", "Ana", "estou de volta")
        val outcome = coordinator(store, ok).onConversationActivity("com.whatsapp", "Ana", false, "key-1")

        assertEquals(TriggerOutcome.Sent(id), outcome)
        assertEquals(ScheduledState.SENT, store.rows[id]!!.scheduledState)
        assertEquals(1, ok.sent.size)
        assertEquals("estou de volta", ok.sent.single().third)
        assertEquals("key-1", store.rows[id]!!.triggerNotificationKey)
        assertEquals(1_000L, store.rows[id]!!.sentAt)
    }

    @Test
    fun `segunda notificacao depois do envio nao dispara de novo`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Accepted }
        val c = coordinator(store, sender)
        store.arm("com.whatsapp", "Ana", "oi")

        c.onConversationActivity("com.whatsapp", "Ana", false, "key-1")
        val second = c.onConversationActivity("com.whatsapp", "Ana", false, "key-2")

        assertEquals(TriggerOutcome.NothingArmed, second)
        assertEquals("uma notificação a mais não pode virar uma mensagem a mais", 1, sender.sent.size)
    }

    // ---- idempotência do claim ----------------------------------------------

    @Test
    fun `notificacao duplicada perde o claim e nao envia nada`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Accepted }
        val id = store.arm("com.whatsapp", "Ana", "oi")
        // Outra execução tomou posse entre a leitura e o claim — exatamente o que a
        // notificação agrupada/atualizada faz na prática.
        store.beforeClaim = { store.stealClaim(it) }

        val outcome = coordinator(store, sender).onConversationActivity("com.whatsapp", "Ana", false, "key-dup")

        assertEquals(TriggerOutcome.LostClaim, outcome)
        assertTrue("perder o claim não pode enviar", sender.sent.isEmpty())
        assertEquals(ScheduledState.CLAIMED, store.rows[id]!!.scheduledState)
    }

    @Test
    fun `dez notificacoes na mesma rajada entregam exatamente uma vez`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Accepted }
        store.arm("com.whatsapp", "Ana", "oi")
        val c = coordinator(store, sender)

        repeat(10) { c.onConversationActivity("com.whatsapp", "Ana", false, "key-$it") }

        assertEquals(1, sender.sent.size)
    }

    // ---- o que não é gatilho -------------------------------------------------

    @Test
    fun `mensagem do proprio usuario nao e gatilho`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Accepted }
        val id = store.arm("com.whatsapp", "Ana", "oi")

        val outcome = coordinator(store, sender)
            .onConversationActivity("com.whatsapp", "Ana", fromSelf = true, triggerNotificationKey = "key-eco")

        assertEquals(TriggerOutcome.OwnMessage, outcome)
        assertTrue(sender.sent.isEmpty())
        assertEquals(ScheduledState.PENDING, store.rows[id]!!.scheduledState)
    }

    @Test
    fun `mensagem cancelada nao dispara`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Accepted }
        val id = store.arm("com.whatsapp", "Ana", "oi")
        store.cancel(id)

        val outcome = coordinator(store, sender).onConversationActivity("com.whatsapp", "Ana", false, "key-1")

        assertEquals(TriggerOutcome.NothingArmed, outcome)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `conversa de outra conta nao dispara a mensagem armada`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Accepted }
        val id = store.arm("com.whatsapp", "Ana", "oi")

        val outcome = coordinator(store, sender)
            .onConversationActivity("com.whatsapp.w4b", "Ana", false, "key-1")

        assertEquals(TriggerOutcome.NothingArmed, outcome)
        assertEquals(ScheduledState.PENDING, store.rows[id]!!.scheduledState)
    }

    // ---- falha ---------------------------------------------------------------

    @Test
    fun `falha volta a PENDING com tentativa registrada e espera antes do proximo gatilho`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Rejected(NotificationReplySender.NO_ACTION) }
        val id = store.arm("com.whatsapp", "Ana", "oi")

        val outcome = coordinator(store, sender).onConversationActivity("com.whatsapp", "Ana", false, "key-1")

        assertEquals(TriggerOutcome.Retrying(id, 1, NotificationReplySender.NO_ACTION), outcome)
        val row = store.rows[id]!!
        assertEquals(ScheduledState.PENDING, row.scheduledState)
        assertEquals(1, row.attempts)
        assertEquals(NotificationReplySender.NO_ACTION, row.lastError)
        assertEquals(61_000L, row.nextAttemptAt)
        assertNull("falhar nunca pode carimbar sentAt", row.sentAt)
    }

    @Test
    fun `dentro da janela de backoff um novo gatilho nao tenta de novo`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Rejected("sem ação") }
        store.arm("com.whatsapp", "Ana", "oi")
        val c = coordinator(store, sender, now = 1_000L)

        c.onConversationActivity("com.whatsapp", "Ana", false, "key-1")
        val second = c.onConversationActivity("com.whatsapp", "Ana", false, "key-2")

        assertEquals("backoff é o que impede o loop apertado", TriggerOutcome.NothingArmed, second)
        assertEquals(1, sender.sent.size)
    }

    @Test
    fun `tentativas esgotadas viram FAILED terminal e nunca SENT`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Rejected(NotificationReplySender.NO_ACTION) }
        val id = store.arm("com.whatsapp", "Ana", "oi")

        // Cada gatilho avança uma tentativa; o backoff é contornado avançando o relógio.
        var outcome: TriggerOutcome? = null
        for (t in listOf(1_000L, 100_000L, 200_000L)) {
            outcome = ScheduledMessageCoordinator(
                store = store, sender = sender, clock = { t }, maxAttempts = 3, retryBackoffMs = 60_000L
            ).onConversationActivity("com.whatsapp", "Ana", false, "key-$t")
        }

        assertEquals(TriggerOutcome.Failed(id, NotificationReplySender.NO_ACTION), outcome)
        val row = store.rows[id]!!
        assertEquals(ScheduledState.FAILED, row.scheduledState)
        assertEquals(3, row.attempts)
        assertNull(row.sentAt)
        assertEquals(3, sender.sent.size)
    }

    @Test
    fun `excecao no envio nao deixa a linha presa em CLAIMED`() = runBlocking {
        val store = FakeStore()
        val sender = ThrowingSender()
        val id = store.arm("com.whatsapp", "Ana", "oi")

        val outcome = coordinator(store, sender).onConversationActivity("com.whatsapp", "Ana", false, "key-1")

        assertTrue(outcome is TriggerOutcome.Retrying)
        assertEquals(ScheduledState.PENDING, store.rows[id]!!.scheduledState)
        assertTrue(store.rows[id]!!.lastError!!.contains("IllegalStateException"))
        assertNull(store.rows[id]!!.sentAt)
    }

    // ---- ordem ---------------------------------------------------------------

    @Test
    fun `duas armadas saem uma por gatilho na ordem em que foram armadas`() = runBlocking {
        val store = FakeStore()
        val sender = RecordingSender { ReplyResult.Accepted }
        store.arm("com.whatsapp", "Ana", "primeira", createdAt = 1L)
        store.arm("com.whatsapp", "Ana", "segunda", createdAt = 2L)
        val c = coordinator(store, sender)

        c.onConversationActivity("com.whatsapp", "Ana", false, "key-1")
        assertEquals("um gatilho entrega no máximo uma mensagem", 1, sender.sent.size)

        c.onConversationActivity("com.whatsapp", "Ana", false, "key-2")
        assertEquals(listOf("primeira", "segunda"), sender.sent.map { it.third })
    }
}
