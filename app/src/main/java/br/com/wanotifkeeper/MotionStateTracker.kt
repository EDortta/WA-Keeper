package br.com.wanotifkeeper

/**
 * Estado puro de movimento, separado dos sensores Android para permitir teste determinístico.
 *
 * A entrada em movimento é imediata. A saída exige ausência de atividade por [stillnessMs],
 * evitando oscilar entre parado/em movimento por pequenos intervalos sem aceleração.
 */
class MotionStateTracker(
    private val stillnessMs: Long
) {
    private var lastActivityAt: Long? = null

    fun markActivity(now: Long) {
        lastActivityAt = now
    }

    fun isInMotion(now: Long): Boolean {
        val last = lastActivityAt ?: return false
        return now - last < stillnessMs
    }

    fun reset() {
        lastActivityAt = null
    }
}
