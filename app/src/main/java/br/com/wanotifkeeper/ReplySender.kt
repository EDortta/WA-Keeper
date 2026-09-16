package br.com.wanotifkeeper

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

sealed class ReplyResult {
    object Accepted : ReplyResult()
    data class Rejected(val reason: String, val consumesAttempt: Boolean = true) : ReplyResult()
}

/** Fronteira do mecanismo de envio. Mantém URI como String para a máquina de estados não depender de Android. */
interface ReplySender {
    suspend fun send(packageName: String, sender: String, text: String): ReplyResult

    suspend fun sendMedia(
        packageName: String,
        sender: String,
        text: String,
        uri: String,
        mimeType: String
    ): ReplyResult = ReplyResult.Rejected(MEDIA_NOT_SUPPORTED, consumesAttempt = false)

    companion object {
        const val MEDIA_NOT_SUPPORTED = "a ação de resposta atual não aceita mídia"
    }
}

object ReplyActionRegistry {

    class CachedReply(
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val resultKey: String,
        val notificationKey: String
    )

    private val cache = ConcurrentHashMap<String, CachedReply>()

    fun key(packageName: String, sender: String) = "$packageName|$sender"

    fun remember(
        packageName: String,
        sender: String,
        notificationKey: String,
        actions: Array<Notification.Action>?
    ): String? {
        val action = actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return null
        val remoteInputs = action.remoteInputs ?: return null
        val actionIntent = action.actionIntent ?: return null
        val resultKey = remoteInputs.first().resultKey
        cache[key(packageName, sender)] = CachedReply(
            actionIntent = actionIntent,
            remoteInputs = remoteInputs,
            resultKey = resultKey,
            notificationKey = notificationKey
        )
        return resultKey
    }

    fun forget(notificationKey: String) {
        val stale = cache.entries.firstOrNull { it.value.notificationKey == notificationKey }?.key ?: return
        cache.remove(stale)
    }

    fun get(packageName: String, sender: String): CachedReply? = cache[key(packageName, sender)]

    fun clear() = cache.clear()
}

class NotificationReplySender(private val context: Context) : ReplySender {

    override suspend fun send(packageName: String, sender: String, text: String): ReplyResult {
        val cached = ReplyActionRegistry.get(packageName, sender)
            ?: return ReplyResult.Rejected(NO_ACTION, consumesAttempt = false)

        return runCatching {
            val intent = Intent()
            val results = Bundle().apply { putCharSequence(cached.resultKey, text) }
            RemoteInput.addResultsToIntent(cached.remoteInputs, intent, results)
            cached.actionIntent.send(context, 0, intent)
            ReplyResult.Accepted as ReplyResult
        }.getOrElse { e -> rejectedAfterException(cached, e) }
    }

    override suspend fun sendMedia(
        packageName: String,
        sender: String,
        text: String,
        uri: String,
        mimeType: String
    ): ReplyResult {
        val cached = ReplyActionRegistry.get(packageName, sender)
            ?: return ReplyResult.Rejected(NO_ACTION, consumesAttempt = false)

        val dataInput = cached.remoteInputs.firstOrNull { remote ->
            remote.allowedDataTypes.any { allowed -> mimeMatches(allowed, mimeType) }
        } ?: return ReplyResult.Rejected(ReplySender.MEDIA_NOT_SUPPORTED, consumesAttempt = false)

        return runCatching {
            val parsedUri = Uri.parse(uri)
            runCatching {
                context.grantUriPermission(packageName, parsedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val intent = Intent().apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            RemoteInput.addDataResultToIntent(dataInput, intent, mapOf(mimeType to parsedUri))

            if (text.isNotBlank()) {
                val textInput = cached.remoteInputs.firstOrNull { it.allowFreeFormInput }
                if (textInput != null) {
                    val results = Bundle().apply { putCharSequence(textInput.resultKey, text) }
                    RemoteInput.addResultsToIntent(cached.remoteInputs, intent, results)
                }
            }

            cached.actionIntent.send(context, 0, intent)
            ReplyResult.Accepted as ReplyResult
        }.getOrElse { e -> rejectedAfterException(cached, e) }
    }

    private fun rejectedAfterException(
        cached: ReplyActionRegistry.CachedReply,
        e: Throwable
    ): ReplyResult {
        ReplyActionRegistry.forget(cached.notificationKey)
        return ReplyResult.Rejected("${e.javaClass.simpleName}: ${e.message ?: "sem detalhe"}")
    }

    private fun mimeMatches(allowed: String, actual: String): Boolean {
        if (allowed == "*/*" || allowed.equals(actual, ignoreCase = true)) return true
        val allowedMajor = allowed.substringBefore('/', missingDelimiterValue = allowed)
        val actualMajor = actual.substringBefore('/', missingDelimiterValue = actual)
        return allowed.endsWith("/*") && allowedMajor.equals(actualMajor, ignoreCase = true)
    }

    companion object {
        const val NO_ACTION = "notificação sem ação de resposta compatível (RemoteInput ausente)"
    }
}
