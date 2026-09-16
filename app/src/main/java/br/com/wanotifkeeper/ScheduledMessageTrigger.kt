package br.com.wanotifkeeper

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

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

    suspend fun onIncoming(ctx: Context, sbn: StatusBarNotification, sender: String): TriggerOutcome {
        val fromSelf = looksLikeOwnMessage(sbn.notification)
        val outcome = coordinator(ctx).onConversationActivity(
            packageName = sbn.packageName,
            conversationSender = sender,
            fromSelf = fromSelf,
            triggerNotificationKey = sbn.key
        )
        if (outcome !is TriggerOutcome.NothingArmed) {
            android.util.Log.d(TAG, "${sbn.packageName}|$sender -> $outcome")
        }

        if (!fromSelf) {
            val dao = NotifDatabase.get(ctx).scheduled()
            val due = dao.dueTimedForConversation(sbn.packageName, sender, System.currentTimeMillis())
            if (due.isNotEmpty()) {
                due.forEach { row ->
                    val timedOutcome = coordinator(ctx).onTimedMessage(row.id)
                    android.util.Log.d(TAG, "recovery#${row.id} ${sbn.packageName}|$sender -> $timedOutcome")
                }
                ScheduledMessageAlarmScheduler.reschedule(ctx)
            }
        }

        return outcome
    }

    suspend fun onTime(ctx: Context): List<TriggerOutcome> {
        val dao = NotifDatabase.get(ctx).scheduled()
        val due = dao.dueTimed(System.currentTimeMillis())
        if (due.isEmpty()) return emptyList()

        return due.map { row ->
            coordinator(ctx).onTimedMessage(row.id).also { outcome ->
                android.util.Log.d(TAG, "time#${row.id} ${row.packageName}|${row.sender} -> $outcome")
            }
        }
    }

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
        if (own) {
            android.util.Log.d(
                TAG,
                "eco do próprio usuário ignorado (messages=$hasMessages, history=${!history.isNullOrEmpty()})"
            )
        }
        return own
    }
}
