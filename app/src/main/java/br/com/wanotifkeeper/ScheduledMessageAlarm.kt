package br.com.wanotifkeeper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Acorda o app para mensagens AT_TIME. Usa setAndAllowWhileIdle: não exige a permissão especial
 * de alarmes exatos do Android 12+, portanto o sistema pode deslocar alguns minutos para poupar
 * bateria.
 */
object ScheduledMessageAlarmScheduler {
    private const val ACTION = "br.com.wanotifkeeper.SEND_SCHEDULED_MESSAGES"
    private const val REQUEST_CODE = 9042

    suspend fun reschedule(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(app)
        alarm.cancel(pendingIntent)

        val nextAt = NotifDatabase.get(app).scheduled()
            .nextTimedAt(NotificationReplySender.NO_ACTION) ?: return
        val target = max(nextAt, System.currentTimeMillis() + 1_000L)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pendingIntent)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScheduledMessageAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

class ScheduledMessageAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScheduledMessageTrigger.onTime(context.applicationContext)
                ScheduledMessageAlarmScheduler.reschedule(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
