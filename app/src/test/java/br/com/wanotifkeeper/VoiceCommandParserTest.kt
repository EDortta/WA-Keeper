package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Cobre o comando "mostra as N últimas mensagens de X" — incluindo a frase exata que Esteban
 * relatou sem resposta nenhuma ("Godofredo, mostra as dez últimas mensagens de Nanda").
 *
 * Achado da investigação: o parser SEMPRE reconhecia essa frase (verbo "mostra" já estava em
 * [VoiceGrammar.READ_LAST_VERBS]) — o bug real era que "dez" (número por extenso) não batia
 * com a extração de contagem, que só procurava `\d+`, e caía pro padrão de 5 mensagens sem
 * avisar. Corrigido reaproveitando [VoiceGrammar.NUMBER_WORDS] como fallback. Isso por si só
 * não explica "nenhuma resposta" (um parse bem-sucedido sempre fala alguma coisa, nem que seja
 * "não encontrei") — ver o relatório da investigação pra outras hipóteses (gate fechado no
 * momento da fala, ou erro de transcrição no aparelho real).
 */
class VoiceCommandParserTest {

    @Test
    fun `reported utterance - mostra as dez ultimas mensagens de Nanda`() {
        val parsed = VoiceCommandParser.parse("Godofredo, mostra as dez últimas mensagens de Nanda")
        assertNotNull("esperava reconhecer o comando", parsed)
        assertEquals("nanda", parsed!!.command.target)
        assertEquals(10, parsed.command.count)
    }

    @Test
    fun `control utterance - leia as ultimas mensagens de Nanda (no count)`() {
        val parsed = VoiceCommandParser.parse("Godofredo, leia as últimas mensagens de Nanda")
        assertNotNull(parsed)
        assertEquals("nanda", parsed!!.command.target)
        assertEquals(VoiceGrammar.DEFAULT_READ_COUNT, parsed.command.count)
    }

    @Test
    fun `mostra without explicit digit still works, defaults count`() {
        val parsed = VoiceCommandParser.parse("Godofredo, mostra as últimas mensagens de Nanda")
        assertNotNull(parsed)
        assertEquals(VoiceGrammar.DEFAULT_READ_COUNT, parsed!!.command.count)
    }

    @Test
    fun `explicit digit count works (five)`() {
        val parsed = VoiceCommandParser.parse("Godofredo, mostra as 5 últimas mensagens de Nanda")
        assertNotNull(parsed)
        assertEquals(5, parsed!!.command.count)
    }
}
