package br.com.wanotifkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementGuardTest {

    @Test
    fun `sending e anunciado uma vez por operacao`() {
        val guard = AnnouncementGuard()
        assertTrue(guard.shouldAnnounce("com.whatsapp", "key-1", "Ana", "Sending…", 1_000))
        assertFalse(guard.shouldAnnounce("com.whatsapp", "key-1", "Ana", "Sending...", 5_000))
    }

    @Test
    fun `sending volta a poder ser anunciado depois da janela`() {
        val guard = AnnouncementGuard()
        assertTrue(guard.shouldAnnounce("com.whatsapp", "key-1", "Ana", "Enviando", 1_000))
        assertTrue(
            guard.shouldAnnounce(
                "com.whatsapp",
                "key-1",
                "Ana",
                "Enviando",
                1_000 + AnnouncementGuard.SENDING_TTL_MS + 1
            )
        )
    }

    @Test
    fun `localizacao repetida nao e relida`() {
        val guard = AnnouncementGuard()
        assertTrue(guard.shouldAnnounce("com.whatsapp", "key-1", "Ana", "📍 Localização", 1_000))
        assertFalse(guard.shouldAnnounce("com.whatsapp", "key-1", "Ana", "Localização", 8_000))
    }

    @Test
    fun `nova notificacao de localizacao continua permitida`() {
        val guard = AnnouncementGuard()
        assertTrue(guard.shouldAnnounce("com.whatsapp", "key-1", "Ana", "Location", 1_000))
        assertTrue(guard.shouldAnnounce("com.whatsapp", "key-2", "Ana", "Location", 2_000))
    }

    @Test
    fun `mensagem comum nao sofre cooldown adicional`() {
        val guard = AnnouncementGuard()
        assertTrue(guard.shouldAnnounce("com.whatsapp", "key-1", "Ana", "ok", 1_000))
        assertTrue(guard.shouldAnnounce("com.whatsapp", "key-1", "Ana", "ok", 1_100))
    }
}
