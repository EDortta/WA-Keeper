package br.com.wanotifkeeper

import android.content.Context
import android.net.Uri

enum class DriveBackupMode { INHERIT, ENABLED, DISABLED }

object DriveBackupPolicy {
    private const val FILE = "wa_keeper_drive_backup"
    private const val KEY_ROOT_URI = "root_uri"
    private const val KEY_GLOBAL = "global_enabled"
    private const val KEY_PACKAGE_PREFIX = "package_"
    private const val KEY_ENTITY_PREFIX = "entity_"
    private const val KEY_CONVERSATION_PREFIX = "conversation_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun rootUri(context: Context): Uri? =
        prefs(context).getString(KEY_ROOT_URI, null)?.let(Uri::parse)

    fun setRootUri(context: Context, uri: Uri?) {
        prefs(context).edit().apply {
            if (uri == null) remove(KEY_ROOT_URI)
            else putString(KEY_ROOT_URI, uri.toString())
        }.apply()
    }

    fun globalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GLOBAL, false)

    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLOBAL, enabled).apply()
    }

    fun packageMode(context: Context, packageName: String): DriveBackupMode =
        readMode(context, KEY_PACKAGE_PREFIX + packageName)

    fun setPackageMode(context: Context, packageName: String, mode: DriveBackupMode) =
        writeMode(context, KEY_PACKAGE_PREFIX + packageName, mode)

    fun entityMode(context: Context, entityId: Long): DriveBackupMode =
        readMode(context, KEY_ENTITY_PREFIX + entityId)

    fun setEntityMode(context: Context, entityId: Long, mode: DriveBackupMode) =
        writeMode(context, KEY_ENTITY_PREFIX + entityId, mode)

    fun conversationMode(context: Context, packageName: String, sender: String): DriveBackupMode =
        readMode(context, KEY_CONVERSATION_PREFIX + conversationKey(packageName, sender))

    fun setConversationMode(
        context: Context,
        packageName: String,
        sender: String,
        mode: DriveBackupMode
    ) = writeMode(context, KEY_CONVERSATION_PREFIX + conversationKey(packageName, sender), mode)

    suspend fun effectiveEnabled(
        context: Context,
        packageName: String,
        sender: String
    ): Boolean {
        modeValue(conversationMode(context, packageName, sender))?.let { return it }

        val entityId = NotifDatabase.get(context)
            .memory()
            .linkForConversation(packageName, sender)
            ?.entityId
        if (entityId != null) {
            modeValue(entityMode(context, entityId))?.let { return it }
        }

        modeValue(packageMode(context, packageName))?.let { return it }
        return globalEnabled(context)
    }

    suspend fun inheritedDescription(
        context: Context,
        packageName: String,
        sender: String
    ): String = if (effectiveEnabled(context, packageName, sender)) "salvar" else "não salvar"

    fun next(mode: DriveBackupMode): DriveBackupMode = when (mode) {
        DriveBackupMode.INHERIT -> DriveBackupMode.ENABLED
        DriveBackupMode.ENABLED -> DriveBackupMode.DISABLED
        DriveBackupMode.DISABLED -> DriveBackupMode.INHERIT
    }

    private fun conversationKey(packageName: String, sender: String) =
        packageName + "|" + sender.trim().lowercase()

    private fun readMode(context: Context, key: String): DriveBackupMode {
        val raw = prefs(context).getString(key, DriveBackupMode.INHERIT.name)
        return runCatching { DriveBackupMode.valueOf(raw ?: DriveBackupMode.INHERIT.name) }
            .getOrDefault(DriveBackupMode.INHERIT)
    }

    private fun writeMode(context: Context, key: String, mode: DriveBackupMode) {
        prefs(context).edit().putString(key, mode.name).apply()
    }

    private fun modeValue(mode: DriveBackupMode): Boolean? = when (mode) {
        DriveBackupMode.INHERIT -> null
        DriveBackupMode.ENABLED -> true
        DriveBackupMode.DISABLED -> false
    }
}
