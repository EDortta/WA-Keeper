package br.com.wanotifkeeper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // O NotificationListenerService continua sendo gerenciado pelo sistema. Aqui só
        // reconstruímos o próximo alarme persistido no Room, porque AlarmManager perde alarmes
        // no reboot.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScheduledMessageAlarmScheduler.reschedule(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
