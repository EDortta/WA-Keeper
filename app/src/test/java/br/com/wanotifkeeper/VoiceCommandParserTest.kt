package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Cobre o comando "verbo + as N últimas mensagens de X", incluindo duas rodadas de relatos
 * reais de Esteban de "nenhuma resposta":
 *
 * 1. "Godofredo, mostra as dez últimas mensagens de Nanda" — o parser sempre reconhecia essa
 *    frase (verbo "mostra" já estava em [VoiceGrammar.READ_LAST_VERBS]); o bug era "dez" (por
 *    extenso) não bater com a extração de contagem (só olhava `\d+`), caindo pro padrão de 5
 *    sem avisar. Corrigido reaproveitando [VoiceGrammar.NUMBER_WORDS] como fallback.
 * 2. "Godofredo" (sozinho) seguido, dentro da janela de graça, de "fala para mim as últimas
 *    cinco mensagens da Nanda" — captura ao vivo confirmou que o reconhecedor ouviu certinho,
 *    mas "fala"/"falar" não estavam em READ_LAST_VERBS, então o parser ignorava em silêncio
 *    por design (nunca adivinha). Corrigido adicionando os dois verbos.
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

    /**
     * Achado numa segunda rodada de investigação, com captura ao vivo de fala real (não mais
     * síntese): Esteban disse "Godofredo" sozinho, e no turno seguinte (dentro da janela de
     * graça) "fala para mim as últimas cinco mensagens da Nanda" — reconhecido palavra por
     * palavra pelo motor (log confirma a transcrição exata), mas "fala" não estava em
     * [VoiceGrammar.READ_LAST_VERBS], então o parser ignorava em silêncio por design (nunca
     * adivinha). É esse o gap real por trás do "eu falo falo falo e não faz nada" — não um loop
     * travado no motor (o generation counter e os logs mostram o ciclo normal de
     * escuta/NO_MATCH/reescuta, sem nenhum erro).
     */
    @Test
    fun `reported utterance - fala para mim as ultimas cinco mensagens da Nanda (grace window, no wake word repeated)`() {
        val parsed = VoiceCommandParser.parseCommandOnly("fala para mim as ultimas cinco mensagens da Nanda")
        assertNotNull("esperava reconhecer 'fala' como verbo de comando", parsed)
        assertEquals("nanda", parsed!!.command.target)
        assertEquals(5, parsed.command.count)
    }

    @Test
    fun `falar also works as a trigger verb`() {
        val parsed = VoiceCommandParser.parse("Godofredo, pode falar as últimas mensagens de Nanda")
        assertNotNull(parsed)
        assertEquals("nanda", parsed!!.command.target)
    }

    @Test
    fun `bare wake word alone does not parse as a command`() {
        assertEquals(null, VoiceCommandParser.parse("Godofredo"))
    }

    @Test
    fun `bare wake word is still detected for the grace window`() {
        assert(VoiceCommandParser.hasWakeWord("Godofredo"))
    }
}
