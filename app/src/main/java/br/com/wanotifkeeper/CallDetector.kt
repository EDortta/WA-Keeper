package br.com.wanotifkeeper

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Detecta "em ligação" pelo modo de áudio do sistema (MODE_IN_CALL) — é o próprio
 * framework de telefonia quem muda esse modo ao atender uma chamada GSM/VoLTE e quem
 * devolve a NORMAL quando ela termina. Evita pedir READ_PHONE_STATE: sem permissão
 * nova, sem diálogo de runtime, no mesmo espírito do [MotionDetector].
 *
 * Não há callback do sistema para essa transição, então fazemos polling leve
 * (uma leitura de estado) enquanto [start] estiver ativo.
 */
class CallDetector(context: Context, private val onCallEnded: () -> Unit) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile private var lastInCall = false
    private var job: Job? = null

    fun isInCall(): Boolean = audioManager?.mode == AudioManager.MODE_IN_CALL

    fun start(scope: CoroutineScope) {
        if (job != null || audioManager == null) return
        job = scope.launch {
            while (isActive) {
                val now = isInCall()
                if (lastInCall && !now) onCallEnded()
                lastInCall = now
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val POLL_MS = 3000L
    }
}
