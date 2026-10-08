package br.com.wanotifkeeper

import kotlinx.coroutines.sync.Mutex

/**
 * Serializa qualquer envio que precise controlar a interface do WhatsApp.
 *
 * RemoteInput não passa por aqui porque não abre UI. Automação por Accessibility,
 * sim: nunca podem existir dois despachos concorrentes nem um despacho roubando a
 * tela enquanto o usuário está compondo/agendando.
 */
object AutomatedSendGate {
    const val USER_BUSY =
        "envio automático adiado: usuário está configurando uma mensagem"

    private val mutex = Mutex()
    @Volatile private var userEditing = false

    fun setUserEditing(editing: Boolean) {
        userEditing = editing
        if (editing) {
            MediaShareAutomation.cancelForUserInteraction()
            DirectContactAutomation.cancelForUserInteraction()
        }
    }

    fun isUserEditing(): Boolean = userEditing

    class Lease internal constructor() {
        fun release() {
            mutex.unlock()
        }
    }

    suspend fun acquire(): Lease? {
        if (userEditing) return null
        mutex.lock()
        if (userEditing) {
            mutex.unlock()
            return null
        }
        return Lease()
    }

    fun busyResult(): ReplyResult =
        ReplyResult.Rejected(USER_BUSY, consumesAttempt = false)
}
