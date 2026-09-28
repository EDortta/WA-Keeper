package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Test

class BankModeTest {

    @Test
    fun normalWhenModeOffAndBothSpecialAccessesEnabled() {
        assertEquals(
            BankModeStatus.NORMAL,
            BankMode.status(
                bankModeEnabled = false,
                accessibilityEnabled = true,
                notificationAccessEnabled = true
            )
        )
    }

    @Test
    fun waitsForAccessibilityFirst() {
        assertEquals(
            BankModeStatus.WAITING_ACCESSIBILITY,
            BankMode.status(
                bankModeEnabled = true,
                accessibilityEnabled = true,
                notificationAccessEnabled = true
            )
        )
    }

    @Test
    fun thenWaitsForNotificationAccess() {
        assertEquals(
            BankModeStatus.WAITING_NOTIFICATION_ACCESS,
            BankMode.status(
                bankModeEnabled = true,
                accessibilityEnabled = false,
                notificationAccessEnabled = true
            )
        )
    }

    @Test
    fun readyOnlyWhenBothSpecialAccessesAreOff() {
        assertEquals(
            BankModeStatus.READY,
            BankMode.status(
                bankModeEnabled = true,
                accessibilityEnabled = false,
                notificationAccessEnabled = false
            )
        )
    }

    @Test
    fun resumeReportsMissingNotificationAccess() {
        assertEquals(
            BankModeStatus.RESUME_NEEDS_NOTIFICATION_ACCESS,
            BankMode.status(
                bankModeEnabled = false,
                accessibilityEnabled = true,
                notificationAccessEnabled = false
            )
        )
    }
}
