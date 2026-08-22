package br.com.wanotifkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guarda de regressão do bug do beep: com todas as outras condições favoráveis, desligar o
 * switch mestre ([masterEnabled] = false) tem que resultar em "não ouvir" — sempre, sem
 * exceção. Era exatamente essa combinação que, no NotifListenerService antigo, só era
 * reavaliada a cada VOICE_GATE_CHECK_MS (polling) em vez de na hora em que a preferência
 * mudava (ver [Prefs.registerChangeListener] e o listener em NotifListenerService).
 */
class VoiceGateDecisionTest {

    private fun allFavorableExcept(
        masterEnabled: Boolean = true,
        sdkSupported: Boolean = true,
        hasRecordAudioPermission: Boolean = true,
        inCall: Boolean = false,
        gateOpen: Boolean = true
    ) = VoiceGateDecision.shouldListen(
        masterEnabled = masterEnabled,
        sdkSupported = sdkSupported,
        hasRecordAudioPermission = hasRecordAudioPermission,
        inCall = inCall,
        gateOpen = gateOpen
    )

    @Test
    fun `everything favorable — should listen`() {
        assertTrue(allFavorableExcept())
    }

    @Test
    fun `master switch off wins over every other favorable condition`() {
        assertFalse(allFavorableExcept(masterEnabled = false))
    }

    @Test
    fun `unsupported SDK blocks listening even with the switch on`() {
        assertFalse(allFavorableExcept(sdkSupported = false))
    }

    @Test
    fun `missing RECORD_AUDIO permission blocks listening`() {
        assertFalse(allFavorableExcept(hasRecordAudioPermission = false))
    }

    @Test
    fun `an active call blocks listening regardless of the gate`() {
        assertFalse(allFavorableExcept(inCall = true))
    }

    @Test
    fun `closed gate (no motion, no manual timer) blocks listening`() {
        assertFalse(allFavorableExcept(gateOpen = false))
    }

    @Test
    fun `master switch off combined with every other condition also off`() {
        assertFalse(
            VoiceGateDecision.shouldListen(
                masterEnabled = false,
                sdkSupported = false,
                hasRecordAudioPermission = false,
                inCall = true,
                gateOpen = false
            )
        )
    }
}
