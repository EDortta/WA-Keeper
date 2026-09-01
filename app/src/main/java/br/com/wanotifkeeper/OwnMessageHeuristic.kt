package br.com.wanotifkeeper

/**
 * "Esta notificação é o eco da mensagem que **nós** acabamos de enviar?"
 *
 * Está separada do Android de propósito: é a única parte da detecção que dá para
 * provar em teste JVM nesta janela. A extração dos sinais a partir da `Notification`
 * fica em [ScheduledMessageTrigger]; a decisão fica aqui.
 *
 * A versão anterior errava por composição: exigia `messages` **vazio** para olhar o
 * histórico de `RemoteInput`. O eco real do WhatsApp mantém as mensagens recebidas
 * **e** acrescenta o histórico — caía fora das duas pernas e passava como gatilho
 * legítimo. Os sinais agora são independentes.
 */
object OwnMessageHeuristic {

    /**
     * @param hasMessages a notificação traz um `MessagingStyle` com mensagens.
     * @param lastMessageHasNoPerson a última mensagem do style não tem `Person` —
     *   convenção do `MessagingStyle` para "mensagem do dono do aparelho".
     * @param hasRemoteInputHistory `EXTRA_REMOTE_INPUT_HISTORY` está preenchido.
     *   Sozinho **não** basta: qualquer resposta inline preenche esse extra — o próprio
     *   usuário respondendo pela gaveta de notificações, Wear, Android Auto — e ele
     *   persiste nos reposts seguintes daquela conversa. Tratá-lo como prova de eco
     *   faria uma resposta pela gaveta suprimir todos os gatilhos daquela conversa, em
     *   silêncio e para sempre. Entre deixar passar um eco e matar a épica calada, o
     *   erro barato é o primeiro — e o eco tem defesa própria, determinística, na
     *   janela pós-entrega do [ScheduledMessageCoordinator].
     */
    fun isOwnMessage(
        hasMessages: Boolean,
        lastMessageHasNoPerson: Boolean,
        hasRemoteInputHistory: Boolean
    ): Boolean {
        if (hasMessages) return lastMessageHasNoPerson
        return hasRemoteInputHistory
    }
}
