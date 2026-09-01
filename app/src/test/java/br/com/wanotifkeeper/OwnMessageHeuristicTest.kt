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
    fun `mensagem do contato nao vira eco so porque alguem ja respondeu inline`() {
        // Rodada 2 do concílio: EXTRA_REMOTE_INPUT_HISTORY é preenchido por QUALQUER
        // resposta inline — inclusive o usuário respondendo pela gaveta — e persiste nos
        // reposts. Tratá-lo como prova de eco faria a mensagem armada nunca sair, calada.
        // A defesa contra o eco passou a ser a janela pós-entrega do coordenador.
        assertFalse(
            "uma resposta pela gaveta não pode suprimir os gatilhos daquela conversa",
            OwnMessageHeuristic.isOwnMessage(
                hasMessages = true, lastMessageHasNoPerson = false, hasRemoteInputHistory = true
            )
        )
    }

    @Test
    fun `historico de resposta sem style nenhum conta como eco`() {
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
