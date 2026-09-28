package br.com.wanotifkeeper

/**
 * Estado puro de movimento com histerese.
 *
 * O problema do modelo antigo era renovar "em movimento" por qualquer amostra isolada acima de
 * um limiar. Vibração de mesa, motor ligado ou ruído do sensor podiam manter o estado vivo para
 * sempre. Agora uma amostra isolada não basta: a continuidade exige um pequeno burst de atividade
 * e a saída pode ser confirmada cedo por silêncio real, com um timeout duro como rede de segurança.
 */
class MotionStateTracker(
    private val hardTimeoutMs: Long = DEFAULT_HARD_TIMEOUT_MS,
    private val quietExitMs: Long = DEFAULT_QUIET_EXIT_MS,
    private val burstWindowMs: Long = DEFAULT_BURST_WINDOW_MS,
    private val burstHitsRequired: Int = DEFAULT_BURST_HITS
) {
    private var moving = false
    private var lastConfirmedMotionAt: Long? = null
    private var quietSince: Long? = null
    private var burstStartedAt: Long? = null
    private var burstHits = 0

    /**
     * Evento forte do sensor TYPE_SIGNIFICANT_MOTION. É confiável o suficiente para entrada
     * imediata, mas não para manter o estado indefinidamente.
     */
    fun markActivity(now: Long) {
        moving = true
        lastConfirmedMotionAt = now
        quietSince = null
        burstStartedAt = null
        burstHits = 0
    }

    /**
     * Observa uma amostra do sensor contínuo.
     *
     * [active] significa atividade claramente acima do ruído.
     * [quiet] significa amostra claramente parada. O intervalo entre os dois é neutro e evita
     * oscilar por valores na fronteira.
     */
    fun observeSample(active: Boolean, quiet: Boolean, now: Long) {
        if (active) {
            quietSince = null
            registerBurstHit(now)
            return
        }

        resetExpiredBurst(now)

        if (moving && quiet) {
            if (quietSince == null) quietSince = now
            if (now - (quietSince ?: now) >= quietExitMs) {
                moving = false
                lastConfirmedMotionAt = null
                burstStartedAt = null
                burstHits = 0
            }
        } else if (!quiet) {
            // Zona neutra: não confirma movimento, mas também não conta como repouso contínuo.
            quietSince = null
        }
    }

    fun isInMotion(now: Long): Boolean {
        if (!moving) return false
        val last = lastConfirmedMotionAt ?: return false
        if (now - last >= hardTimeoutMs) {
            moving = false
            lastConfirmedMotionAt = null
            quietSince = null
            burstStartedAt = null
            burstHits = 0
            return false
        }
        if (quietSince != null && now - quietSince!! >= quietExitMs) {
            moving = false
            lastConfirmedMotionAt = null
            quietSince = null
            burstStartedAt = null
            burstHits = 0
            return false
        }
        return true
    }

    fun reset() {
        moving = false
        lastConfirmedMotionAt = null
        quietSince = null
        burstStartedAt = null
        burstHits = 0
    }

    private fun registerBurstHit(now: Long) {
        val start = burstStartedAt
        if (start == null || now - start > burstWindowMs) {
            burstStartedAt = now
            burstHits = 1
        } else {
            burstHits++
        }

        if (burstHits >= burstHitsRequired) {
            moving = true
            lastConfirmedMotionAt = now
            quietSince = null
            // Mantém a última amostra como início do próximo burst. Assim movimento contínuo
            // renova naturalmente, sem permitir que um único pico esporádico faça o mesmo.
            burstStartedAt = now
            burstHits = 0
        }
    }

    private fun resetExpiredBurst(now: Long) {
        val start = burstStartedAt ?: return
        if (now - start > burstWindowMs) {
            burstStartedAt = null
            burstHits = 0
        }
    }

    companion object {
        const val DEFAULT_HARD_TIMEOUT_MS = 35_000L
        const val DEFAULT_QUIET_EXIT_MS = 12_000L
        const val DEFAULT_BURST_WINDOW_MS = 2_000L
        const val DEFAULT_BURST_HITS = 3
    }
}
