package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Idempotência das notificações do WhatsApp, relatada pelo operador em 2026-09-01:
 * "às vezes o wa-keeper lê duas vezes a mesma mensagem e/ou as guarda também" e
 * "se um contacto manda mais de uma mensagem, às vezes lê a primeira duas vezes e
 * depois a segunda".
 */
class RepostGuardTest {

    private val guard = RepostGuard()
    private val pkg = "com.whatsapp"

    private fun classify(
        text: String,
        messageTime: Long?,
        now: Long,
        hasImage: Boolean = false,
        sender: String = "Ana"
    ) = guard.classify(pkg, sender, text, messageTime, hasImage, now)

    @Test
    fun `mensagem nova e nova`() {
        val d = classify("oi", messageTime = 1_000, now = 1_000)
        assertTrue(d is RepostGuard.Decision.New)
    }

    @Test
    fun `repost imediato da mesma mensagem e descartado`() {
        classify("oi", messageTime = 1_000, now = 1_000)
        val d = classify("oi", messageTime = 1_000, now = 1_150)
        assertTrue("repost tem que ser Drop, senão a mensagem é lida duas vezes", d is RepostGuard.Decision.Drop)
    }

    @Test
    fun `segunda mensagem da mesma pessoa com o mesmo texto e nova`() {
        // "ok" duas vezes de propósito, com timestamps diferentes: são duas mensagens.
        classify("ok", messageTime = 1_000, now = 1_000)
        val d = classify("ok", messageTime = 9_000, now = 9_000)
        assertTrue(d is RepostGuard.Decision.New)
    }

    @Test
    fun `primeira mensagem nao e relida quando a segunda chega`() {
        // O caso relatado: A, depois A+B. A já foi tratada; só B pode ser nova.
        assertTrue(classify("primeira", messageTime = 1_000, now = 1_000) is RepostGuard.Decision.New)
        assertTrue(classify("segunda", messageTime = 5_000, now = 5_000) is RepostGuard.Decision.New)
        // O WhatsApp reposta a conversa: nenhuma das duas pode voltar.
        assertTrue(classify("primeira", messageTime = 1_000, now = 5_050) is RepostGuard.Decision.Drop)
        assertTrue(classify("segunda", messageTime = 5_000, now = 5_100) is RepostGuard.Decision.Drop)
    }

    @Test
    fun `repost tardio com identidade exata continua sendo repost`() {
        // A notificação que traz a imagem chega até 10,5 s depois. Com identidade exata a
        // memória dura minutos, então ela não vira mensagem nova.
        classify("📷 Foto", messageTime = 1_000, now = 1_000)
        val d = classify("📷 Foto", messageTime = 1_000, now = 1_000 + 10_500)
        assertTrue(d is RepostGuard.Decision.AttachImage || d is RepostGuard.Decision.Drop)
    }

    @Test
    fun `repost que traz imagem pede para anexar, uma vez so`() {
        classify("📷 Foto", messageTime = 1_000, now = 1_000, hasImage = false)
        val primeiro = classify("📷 Foto", messageTime = 1_000, now = 3_000, hasImage = true)
        assertTrue("o repost com imagem tem que anexar", primeiro is RepostGuard.Decision.AttachImage)
        val segundo = classify("📷 Foto", messageTime = 1_000, now = 3_500, hasImage = true)
        assertTrue("só a primeira vez anexa", segundo is RepostGuard.Decision.Drop)
    }

    @Test
    fun `notificacao que ja nasce com imagem nao pede anexo depois`() {
        classify("📷 Foto", messageTime = 1_000, now = 1_000, hasImage = true)
        val d = classify("📷 Foto", messageTime = 1_000, now = 2_000, hasImage = true)
        assertTrue(d is RepostGuard.Decision.Drop)
    }

    @Test
    fun `sem MessagingStyle a memoria e curta`() {
        // Sem timestamp por mensagem não dá para distinguir repetição intencional de repost;
        // passada a janela curta, o texto igual volta a valer como mensagem nova.
        classify("ok", messageTime = null, now = 1_000)
        assertTrue(classify("ok", messageTime = null, now = 1_500) is RepostGuard.Decision.Drop)
        assertTrue(classify("ok", messageTime = null, now = 1_000 + RepostGuard.APPROXIMATE_TTL_MS + 1)
            is RepostGuard.Decision.New)
    }

    @Test
    fun `conversas diferentes nao se confundem`() {
        classify("oi", messageTime = 1_000, now = 1_000, sender = "Ana")
        val d = classify("oi", messageTime = 1_000, now = 1_050, sender = "Bruno")
        assertTrue(d is RepostGuard.Decision.New)
    }

    @Test
    fun `a memoria exata expira`() {
        classify("oi", messageTime = 1_000, now = 1_000)
        val d = classify("oi", messageTime = 1_000, now = 1_000 + RepostGuard.EXACT_TTL_MS + 1)
        assertTrue(d is RepostGuard.Decision.New)
    }

    @Test
    fun `o rowId da linha original e o que o anexo espera`() {
        val nova = classify("📷 Foto", messageTime = 1_000, now = 1_000) as RepostGuard.Decision.New
        nova.record.rowId.complete(42L)
        val anexo = classify("📷 Foto", messageTime = 1_000, now = 2_000, hasImage = true)
                as RepostGuard.Decision.AttachImage
        assertEquals(42L, anexo.record.rowId.getCompleted())
    }
}
