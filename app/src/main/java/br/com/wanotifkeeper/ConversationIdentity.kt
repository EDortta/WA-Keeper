package br.com.wanotifkeeper

import android.service.notification.StatusBarNotification

object ConversationIdentity {
    fun canonicalSender(raw: String, packageName: String? = null): String {
        var value = raw
            .removePrefix("WhatsApp: ")
            .trim()

        value = value.replace(
            Regex("""\s*\(\d+\s+mensage(?:m|ns)\)\s*:\s*.+$""", RegexOption.IGNORE_CASE),
            ""
        )

        value = value.replace(
            Regex("""\s*:\s*[↪↩↵].*$"""),
            ""
        )

        if (packageName == "com.whatsapp.w4b" && value.contains(": ")) {
            value = value.substringBefore(": ").trim()
        }

        return value.trim().ifBlank { raw.trim() }
    }

    fun stableKey(
        sbn: StatusBarNotification,
        canonicalTitle: String
    ): String {
        val shortcut = sbn.notification.shortcutId?.trim().orEmpty()
        if (shortcut.isNotBlank()) return "shortcut:$shortcut"

        val tag = sbn.tag?.trim().orEmpty()
        if (tag.isNotBlank()) return "tag:$tag"

        return "title:${sbn.packageName}:${canonicalTitle.lowercase()}"
    }

    fun displayGroupKey(item: NotifEntity): String =
        "conversation:${item.packageName}:${canonicalSender(item.sender, item.packageName).lowercase()}"

    fun isVisibleHomeConversation(item: NotifEntity): Boolean =
        item.packageName != ConversationImportActivity.PACKAGE_IMPORTED &&
            item.sourceType != "WHATSAPP_EXPORT"

    fun sameConversation(
        item: NotifEntity,
        packageName: String,
        sender: String,
        conversationKey: String?
    ): Boolean {
        if (item.packageName != packageName) return false

        val sameTitle = canonicalSender(item.sender, item.packageName)
            .equals(canonicalSender(sender, packageName), ignoreCase = true)
        if (sameTitle) return true

        return !conversationKey.isNullOrBlank() &&
            !item.conversationKey.isNullOrBlank() &&
            item.conversationKey == conversationKey
    }
}
