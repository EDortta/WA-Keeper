package br.com.wanotifkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rodada 1 do concílio achou aqui o furo mais caro da épica: a versão anterior só
 * olhava o histórico de `RemoteInput` quando **não** havia mensagens no style, e o eco
 * real do WhatsApp tem as duas coisas ao mesmo tempo. Este teste existe para que a
 * composição errada não volte.
 */
class OwnMessageHeuristicTest {

    @Test
    fun `eco com mensagens do contato mais historico de resposta e nosso proprio envio`() {
        assertTrue(
            "é exatamente a forma do repost depois de responder por RemoteInput",
            OwnMessageHeuristic.isOwnMessage(
                hasMessages = true, lastMessageHasNoPerson = false, hasRemoteInputHistory = true
            )
        )
    }

    @Test
    fun `historico de resposta sozinho ja basta`() {
        assertTrue(
            OwnMessageHeuristic.isOwnMessage(
                hasMessages = false, lastMessageHasNoPerson = false, hasRemoteInputHistory = true
            )
        )
    }

    @Test
    fun `ultima mensagem sem Person e do dono do aparelho`() {
        assertTrue(
            OwnMessageHeuristic.isOwnMessage(
                hasMessages = true, lastMessageHasNoPerson = true, hasRemoteInputHistory = false
            )
        )
    }

    @Test
    fun `mensagem normal do contato e gatilho legitimo`() {
        assertFalse(
            OwnMessageHeuristic.isOwnMessage(
                hasMessages = true, lastMessageHasNoPerson = false, hasRemoteInputHistory = false
            )
        )
    }

    @Test
    fun `notificacao sem style e sem historico nao e bloqueada`() {
        assertFalse(
            "bloquear por falta de sinal mataria a épica em silêncio",
            OwnMessageHeuristic.isOwnMessage(
                hasMessages = false, lastMessageHasNoPerson = true, hasRemoteInputHistory = false
            )
        )
    }
}
