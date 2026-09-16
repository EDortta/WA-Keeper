package br.com.wanotifkeeper

import android.content.Context

/**
 * Fachada de TTS mantida para os chamadores existentes.
 *
 * A serialização e o foco de áudio não moram mais aqui: toda saída passa pelo [AudioArbiter],
 * que coordena TTS e arquivos de áudio na mesma fila e respeita o semáforo do microfone.
 */
class Speaker(context: Context) {

    private val audio = AudioArbiter.get(context.applicationContext)

    fun announce(sender: String, text: String) {
        audio.announce(sender, text)
    }

    fun say(text: String) {
        audio.say(text)
    }

    /** TTS explícito de uma mensagem, independente de movimento/configuração automática. */
    fun speakText(text: String) {
        audio.speakText(text)
    }

    fun isBusy(): Boolean = audio.isBusy()

    /**
     * O arbiter é compartilhado pelo processo inteiro, inclusive Activities; destruir a fachada
     * do NotificationListenerService não pode derrubar uma reprodução iniciada pela UI.
     */
    fun shutdown() = Unit
}
