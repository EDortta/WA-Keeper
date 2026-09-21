package br.com.wanotifkeeper

import android.service.notification.StatusBarNotification

object ConversationIdentity {
    private val bidiMarks = Regex("[\\u200e\\u200f\\u202a-\\u202e]")

    fun canonicalSender(raw: String, packageName: String? = null): String {
        var value = raw
            .replace(bidiMarks, "")
            .removePrefix("WhatsApp: ")
            .trim()

        value = value.replace(
            Regex("""\s*\(\d+\s+mensage(?:m|ns)\)\s*:\s*.+$""", RegexOption.IGNORE_CASE),
            ""
        )

        value = value.replace(
            Regex("""\s*\(\d+\s+mensage(?:m|ns)\)\s*$""", RegexOption.IGNORE_CASE),
            ""
        )

        value = value.replace(
            Regex("""\s*:\s*[↪↩↵].*$"""),
            ""
        )

        if (packageName == "com.whatsapp.w4b" && value.contains(": ")) {
            value = value.substringBefore(": ").trim()
        }

        return value.trim().ifBlank { raw.replace(bidiMarks, "").trim() }
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

    fun groupKey(packageName: String, sender: String, conversationKey: String?): String {
        val storedKey = conversationKey?.trim().orEmpty()
        return if (storedKey.isNotBlank()) {
            "conversation:$packageName:$storedKey"
        } else {
            "conversation:$packageName:${canonicalSender(sender, packageName).lowercase()}"
        }
    }

    fun displayGroupKey(item: NotifEntity): String =
        groupKey(item.packageName, item.sender, item.conversationKey)

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

        val wantedKey = conversationKey?.trim().orEmpty()
        val itemKey = item.conversationKey?.trim().orEmpty()
        if (wantedKey.isNotBlank() && itemKey.isNotBlank()) {
            return itemKey == wantedKey
        }

        return canonicalSender(item.sender, item.packageName)
            .equals(canonicalSender(sender, packageName), ignoreCase = true)
    }
}
