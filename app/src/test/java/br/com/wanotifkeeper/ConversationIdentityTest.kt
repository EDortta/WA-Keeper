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
            ConversationIdentity.canonicalSender(
                "Open (3 mensagens): Luís Henrique",
                "com.whatsapp.w4b"
            )
        )
    }

    @Test
    fun stripsReplyPreviewDecoration() {
        assertEquals(
            "Open",
            ConversationIdentity.canonicalSender(
                "Open: ↪ Você respondeu",
                "com.whatsapp.w4b"
            )
        )
    }

    @Test
    fun businessLegacyFallbackStripsSuffixAfterColon() {
        assertEquals(
            "Open",
            ConversationIdentity.canonicalSender(
                "Open: Luís Henrique",
                "com.whatsapp.w4b"
            )
        )
    }

    @Test
    fun keepsPlainConversationName() {
        assertEquals(
            "Open",
            ConversationIdentity.canonicalSender("Open", "com.whatsapp.w4b")
        )
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
    fun stableKeyWinsOverVariableTitle() {
        val a = NotifEntity(
            sender = "Open",
            text = "a",
            timestamp = 1L,
            packageName = "com.whatsapp.w4b",
            conversationKey = "shortcut:chat-123"
        )
        val b = NotifEntity(
            sender = "Open: ↪ Você respondeu",
            text = "b",
            timestamp = 2L,
            packageName = "com.whatsapp.w4b",
            conversationKey = "shortcut:chat-123"
        )

        assertEquals(
            ConversationIdentity.displayGroupKey(a),
            ConversationIdentity.displayGroupKey(b)
        )
        assertTrue(
            ConversationIdentity.sameConversation(
                item = b,
                packageName = "com.whatsapp.w4b",
                sender = "Open",
                conversationKey = "shortcut:chat-123"
            )
        )
    }

    @Test
    fun legacyRowsStillGroupByCanonicalTitle() {
        val a = NotifEntity(
            sender = "Open",
            text = "a",
            timestamp = 1L,
            packageName = "com.whatsapp.w4b"
        )
        val b = NotifEntity(
            sender = "Open (3 mensagens): Luís Henrique",
            text = "b",
            timestamp = 2L,
            packageName = "com.whatsapp.w4b"
        )

        assertEquals(
            ConversationIdentity.displayGroupKey(a),
            ConversationIdentity.displayGroupKey(b)
        )
    }
}
