package br.com.wanotifkeeper

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

enum class BankModeStatus {
    NORMAL,
    WAITING_ACCESSIBILITY,
    WAITING_NOTIFICATION_ACCESS,
    READY,
    RESUME_NEEDS_ACCESSIBILITY,
    RESUME_NEEDS_NOTIFICATION_ACCESS
}

object BankMode {
    fun isEnabled(context: Context): Boolean = Prefs.isBankModeEnabled(context)

    fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context,
            MediaShareAccessibilityService::class.java
        ).flattenToString()

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun notificationAccessEnabled(context: Context): Boolean {
        val expected = ComponentName(
            context,
            NotifListenerService::class.java
        ).flattenToString()

        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun status(
        bankModeEnabled: Boolean,
        accessibilityEnabled: Boolean,
        notificationAccessEnabled: Boolean
    ): BankModeStatus {
        return if (bankModeEnabled) {
            when {
                accessibilityEnabled -> BankModeStatus.WAITING_ACCESSIBILITY
                notificationAccessEnabled -> BankModeStatus.WAITING_NOTIFICATION_ACCESS
                else -> BankModeStatus.READY
            }
        } else {
            when {
                !accessibilityEnabled -> BankModeStatus.RESUME_NEEDS_ACCESSIBILITY
                !notificationAccessEnabled -> BankModeStatus.RESUME_NEEDS_NOTIFICATION_ACCESS
                else -> BankModeStatus.NORMAL
            }
        }
    }

    fun status(context: Context): BankModeStatus = status(
        bankModeEnabled = isEnabled(context),
        accessibilityEnabled = accessibilityEnabled(context),
        notificationAccessEnabled = notificationAccessEnabled(context)
    )
}
