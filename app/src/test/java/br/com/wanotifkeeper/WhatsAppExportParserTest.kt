package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppExportParserTest {
    @Test
    fun parsesBrazilianExportAndMultilineMessages() {
        val input = """
            20/09/2026, 10:30 - Fernanda: primeira linha
            segunda linha
            20/09/2026, 10:31 - Esteban: resposta
        """.trimIndent()

        val parsed = WhatsAppExportParser.parse(input)

        assertEquals(2, parsed.size)
        assertEquals("Fernanda", parsed[0].author)
        assertEquals("primeira linha\nsegunda linha", parsed[0].text)
        assertEquals("Esteban", parsed[1].author)
    }

    @Test
    fun parsesBracketedExport() {
        val input = "[20/09/2026, 10:30:05] Fernanda: teste"
        val parsed = WhatsAppExportParser.parse(input)

        assertEquals(1, parsed.size)
        assertEquals("Fernanda", parsed.single().author)
        assertTrue(parsed.single().timestamp > 0)
    }

    @Test
    fun fingerprintIsStableAndConversationScoped() {
        val a = WhatsAppExportParser.fingerprint("Nanda", "Fernanda", 123L, "Oi  tudo bem?")
        val b = WhatsAppExportParser.fingerprint("Nanda", "Fernanda", 123L, "Oi tudo bem?")
        val c = WhatsAppExportParser.fingerprint("Outro", "Fernanda", 123L, "Oi tudo bem?")

        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
