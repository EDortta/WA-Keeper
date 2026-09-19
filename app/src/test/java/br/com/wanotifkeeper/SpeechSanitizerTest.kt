package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSanitizerTest {

    @Test
    fun `pix copia e cola nao e soletrado`() {
        val payload = "00020101021226770014BR.GOV.BCB.PIX2555api.itau/pix/qr/v2/4a1d6a04-8ce4-4b7b-a4e9-1a85c663a55a5204000053039865802BR5906SABESP6009SAOPAULO62070503***63049A30"
        val spoken = SpeechSanitizer.forAutomaticSpeech("Pix Sabesp\n$payload")
        assertTrue(spoken.contains("Pix Sabesp"))
        assertTrue(spoken.contains("Chave Pix anexa"))
        assertFalse(spoken.contains("000201"))
        assertFalse(spoken.contains("BR.GOV.BCB.PIX"))
    }

    @Test
    fun `url vira aviso curto`() {
        val spoken = SpeechSanitizer.forAutomaticSpeech("Veja https://example.com/a/b?c=123 depois")
        assertEquals("Veja depois. URL disponível para visita.", spoken)
    }

    @Test
    fun `url sem protocolo tambem vira aviso curto`() {
        val spoken = SpeechSanitizer.forAutomaticSpeech("Abra exemplo.com/pagamento")
        assertEquals("Abra. URL disponível para visita.", spoken)
    }

    @Test
    fun `email nao e soletrado`() {
        val spoken = SpeechSanitizer.forAutomaticSpeech("Mande para fulano@example.com")
        assertEquals("Mande para. Endereço de e-mail disponível.", spoken)
    }

    @Test
    fun `mensagem comum permanece equivalente`() {
        assertEquals("A parcela da casa está em aberto.", SpeechSanitizer.forAutomaticSpeech("A parcela da casa está em aberto"))
    }
}
