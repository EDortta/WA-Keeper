package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactAnnouncementTrackerTest {

    @Test
    fun firstMessageUsesSingularNotice() {
        val tracker = CompactAnnouncementTracker(resetAfterMs = 120_000L)
        assertEquals(
            "Mensagem de Fulano",
            tracker.phrase("com.whatsapp", "Fulano", 1_000L)
        )
    }

    @Test
    fun subsequentMessagesUseAccumulatedCount() {
        val tracker = CompactAnnouncementTracker(resetAfterMs = 120_000L)

        tracker.phrase("com.whatsapp", "Fulano", 1_000L)
        assertEquals(
            "2 mensagens de Fulano",
            tracker.phrase("com.whatsapp", "Fulano", 2_000L)
        )
        assertEquals(
            "3 mensagens de Fulano",
            tracker.phrase("com.whatsapp", "Fulano", 3_000L)
        )
    }

    @Test
    fun countResetsAfterSilenceWindow() {
        val tracker = CompactAnnouncementTracker(resetAfterMs = 120_000L)

        tracker.phrase("com.whatsapp", "Fulano", 1_000L)
        assertEquals(
            "Mensagem de Fulano",
            tracker.phrase("com.whatsapp", "Fulano", 122_000L)
        )
    }

    @Test
    fun countersAreIndependentByConversationAndAccount() {
        val tracker = CompactAnnouncementTracker(resetAfterMs = 120_000L)

        tracker.phrase("com.whatsapp", "Fulano", 1_000L)
        assertEquals(
            "Mensagem de Fulano",
            tracker.phrase("com.whatsapp.w4b", "Fulano", 2_000L)
        )
        assertEquals(
            "Mensagem de Sicrano",
            tracker.phrase("com.whatsapp", "Sicrano", 3_000L)
        )
    }
}
