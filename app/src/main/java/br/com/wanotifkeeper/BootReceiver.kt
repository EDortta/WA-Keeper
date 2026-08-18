package br.com.wanotifkeeper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // O NotificationListenerService é gerenciado pelo sistema automaticamente.
        // Esse receiver existe para garantir que o app "acorde" após reboot
        // e o sistema reconecte o listener.
    }
}
