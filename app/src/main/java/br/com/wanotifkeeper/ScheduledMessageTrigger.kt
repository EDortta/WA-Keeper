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

    /** Um claim mais velho que isto é resto de um processo que morreu enviando. */
    private const val STALE_CLAIM_MS = 5 * 60_000L

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
     * Chamado **depois** de a ação de resposta desta notificação já ter sido cacheada —
     * é isso que garante que o `PendingIntent` usado no envio é fresco, e não um
     * ponteiro velho de uma notificação que já morreu.
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
     * Uma linha presa em `CLAIMED` é um processo que morreu no meio do envio. Devolve
     * a `PENDING` uma vez, na conexão do listener — nunca em laço, para não virar o
     * loop apertado que a #18 proíbe.
     */
    suspend fun releaseStaleClaims(ctx: Context) {
        val now = System.currentTimeMillis()
        runCatching {
            NotifDatabase.get(ctx).scheduled().releaseStaleClaims(now, now - STALE_CLAIM_MS)
        }
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

        val lastPerson = style?.messages?.lastOrNull()?.person
        if (style != null && style.messages.isNotEmpty() && lastPerson == null) return true

        val history = runCatching {
            notification.extras?.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        }.getOrNull()
        return style?.messages.isNullOrEmpty() && !history.isNullOrEmpty()
    }
}
