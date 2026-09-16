package br.com.wanotifkeeper

import android.content.Context

/**
 * Fachada para reprodução de áudio recebido.
 *
 * O player real é compartilhado em [AudioArbiter], junto com TTS. Assim um áudio recebido não
 * toca por cima de outra mensagem falada e vice-versa.
 */
class AudioPlayer(context: Context) {

    private val audio = AudioArbiter.get(context.applicationContext)

    fun play(path: String) {
        audio.play(path)
    }

    /** O arbiter é singleton do processo; destruir esta fachada não encerra a fila global. */
    fun shutdown() = Unit
}
