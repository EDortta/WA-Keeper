package br.com.wanotifkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationIdentityTest {
    @Test
    fun stripsPendingMessageDecoration() {
        assertEquals(
            "Open",
            ConversationIdentity.canonicalSender("Open (3 mensagens): Luís Henrique")
        )
    }

    @Test
    fun stripsReplyPreviewDecoration() {
        assertEquals(
            "Open",
            ConversationIdentity.canonicalSender("Open: ↪ Você respondeu")
        )
    }

    @Test
    fun keepsPlainConversationName() {
        assertEquals("Open", ConversationIdentity.canonicalSender("Open"))
    }

    @Test
    fun hidesImportedHistoryFromHome() {
        val imported = NotifEntity(
            sender = "Open",
            text = "teste",
            timestamp = 1L,
            packageName = ConversationImportActivity.PACKAGE_IMPORTED,
            sourceType = "WHATSAPP_EXPORT"
        )
        assertFalse(ConversationIdentity.isVisibleHomeConversation(imported))
    }

    @Test
    fun matchesDecoratedAndPlainConversation() {
        assertTrue(
            ConversationIdentity.sameConversation(
                "Open",
                "Open (3 mensagens): Luís Henrique"
            )
        )
    }
}
