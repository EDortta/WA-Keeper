package br.com.wanotifkeeper

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Cola entre o `NotifListenerService` e a máquina de estados da EPIC 4 (#18).
 *
 * Fica aqui, e não no listener, de propósito: o listener é território compartilhado
 * com outra frente, e tudo que é desta épica precisa caber em arquivos próprios.
 * O gancho no listener é uma chamada só.
 */
object ScheduledMessageTrigger {

    const val TAG = "WAK-ScheduledMsg"

    @Volatile private var coordinator: ScheduledMessageCoordinator? = null

    private fun coordinator(ctx: Context): ScheduledMessageCoordinator =
        coordinator ?: synchronized(this) {
            coordinator ?: ScheduledMessageCoordinator(
                store = RoomScheduledMessageStore(NotifDatabase.get(ctx).scheduled()),
                sender = NotificationReplySender(ctx.applicationContext),
                log = { msg -> android.util.Log.d(TAG, msg) }
            ).also { coordinator = it }
        }

    /**
     * Uma notificação nova daquela conversa chegou: se houver mensagem armada, é agora.
     *
     * Chamado **depois** de a ação de resposta desta notificação já ter sido cacheada.
     * Isso torna o `PendingIntent` fresco no caso normal — mas não é garantia: quando
     * esta notificação em particular não traz ação, o `ReplyActionRegistry` mantém a
     * entrada anterior de propósito, e o disparo pode usar um ponteiro de notificação
     * já removida. Nesse caso o `send` falha com `CanceledException` e a entrega volta
     * para a fila; o que não acontece é alegar sucesso.
     */
    suspend fun onIncoming(ctx: Context, sbn: StatusBarNotification, sender: String): TriggerOutcome {
        val outcome = coordinator(ctx).onConversationActivity(
            packageName = sbn.packageName,
            conversationSender = sender,
            fromSelf = looksLikeOwnMessage(sbn.notification),
            triggerNotificationKey = sbn.key
        )
        if (outcome !is TriggerOutcome.NothingArmed) {
            android.util.Log.d(TAG, "${sbn.packageName}|$sender -> $outcome")
        }
        return outcome
    }

    /**
     * A mensagem é do próprio usuário?
     *
     * Na convenção do `MessagingStyle` do Android, uma mensagem **sem** `Person` é a
     * do próprio dono do aparelho. É assim que o eco da nossa própria resposta chega
     * de volta — e a #18 é explícita: mensagem enviada pelo usuário não é gatilho.
     *
     * `EXTRA_REMOTE_INPUT_HISTORY` é o segundo sinal: o Android o preenche com o texto
     * respondido por `RemoteInput`. Se a notificação só carrega isso, é o nosso próprio
     * envio voltando.
     */
    fun looksLikeOwnMessage(notification: Notification): Boolean {
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()
        val history = runCatching {
            notification.extras?.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        }.getOrNull()

        val hasMessages = !style?.messages.isNullOrEmpty()
        val own = OwnMessageHeuristic.isOwnMessage(
            hasMessages = hasMessages,
            lastMessageHasNoPerson = hasMessages && style?.messages?.lastOrNull()?.person == null,
            hasRemoteInputHistory = !history.isNullOrEmpty()
        )
        // O log nomeia os sinais porque a única forma de conferir a premissa
        // ("mensagem sem Person é do dono do aparelho") é olhar um aparelho de verdade —
        // que não existe nesta janela. Ver a pergunta parqueada no RESUME.md da 018.
        if (own) {
            android.util.Log.d(
                TAG,
                "eco do próprio usuário ignorado (messages=$hasMessages, history=${!history.isNullOrEmpty()})"
            )
        }
        return own
    }
}
