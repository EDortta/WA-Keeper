package br.com.wanotifkeeper

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Beep curto para avisar "chegou mensagem" sem interromper a ligação com fala.
 *
 * STREAM_VOICE_CALL é o canal que o próprio Android mistura no áudio da chamada em
 * andamento (a mesma técnica usada por tons de chamada em espera) — em qualquer outro
 * stream o beep não seria ouvido enquanto a chamada ocupa a rota de áudio (ex.: SCO
 * do Bluetooth do carro).
 */
class Beeper {

    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneGenerator.MAX_VOLUME)
    }.getOrNull()

    fun beep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
    }

    fun shutdown() {
        runCatching { toneGenerator?.release() }
    }

    companion object {
        private const val BEEP_DURATION_MS = 150
    }
}
