package br.com.wanotifkeeper

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    }

    fun isUserEditing(): Boolean = userEditing

    suspend fun run(block: suspend () -> ReplyResult): ReplyResult {
        if (userEditing) return ReplyResult.Rejected(USER_BUSY, consumesAttempt = false)
        return mutex.withLock {
            if (userEditing) ReplyResult.Rejected(USER_BUSY, consumesAttempt = false)
            else block()
        }
    }
}
