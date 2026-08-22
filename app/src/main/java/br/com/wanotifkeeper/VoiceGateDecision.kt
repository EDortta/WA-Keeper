package br.com.wanotifkeeper

/**
 * Regra pura (sem nenhuma API do Android) de quando o motor de comandos de voz deve estar
 * ouvindo. Extraída de [NotifListenerService.updateListeningState] justamente para poder ser
 * testada sem instrumentação: foi essa combinação de condições que, com um bug real, deixava
 * o microfone (e o beep de escuta) ligado mesmo depois do switch mestre em Ajustes ser
 * desligado — o serviço só reavaliava a cada [NotifListenerService] VOICE_GATE_CHECK_MS
 * (poll), e nada disparava a reavaliação na hora em que a preferência mudava.
 *
 * O switch mestre ([masterEnabled]) tem que vencer qualquer outra condição — é a garantia
 * que este objeto existe pra proteger.
 */
object VoiceGateDecision {
    fun shouldListen(
        masterEnabled: Boolean,
        sdkSupported: Boolean,
        hasRecordAudioPermission: Boolean,
        inCall: Boolean,
        gateOpen: Boolean
    ): Boolean {
        val enabled = masterEnabled && sdkSupported && hasRecordAudioPermission
        return enabled && !inCall && gateOpen
    }
}
