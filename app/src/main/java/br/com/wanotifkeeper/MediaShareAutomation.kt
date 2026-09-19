package br.com.wanotifkeeper

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Canal de mídia: abre o compartilhamento do WhatsApp e usa Accessibility apenas para
 * escolher exatamente a conversa já conhecida e tocar em Enviar.
 *
 * Não usa heurística aproximada para o contato: se não houver correspondência exata ou
 * houver ambiguidade, aborta em vez de correr o risco de mandar o arquivo à pessoa errada.
 */
object MediaShareAutomation {
    private const val TIMEOUT_MS = 90_000L

    enum class Phase { PICK_CONTACT, SEND }

    data class Pending(
        val packageName: String,
        val sender: String,
        val result: CompletableDeferred<ReplyResult>,
        @Volatile var phase: Phase = Phase.PICK_CONTACT
    )

    @Volatile private var pending: Pending? = null

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, MediaShareAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    suspend fun send(
        context: Context,
        packageName: String,
        sender: String,
        text: String,
        uriText: String,
        mimeType: String
    ): ReplyResult {
        if (!isEnabled(context)) {
            return ReplyResult.Rejected(
                "ative a automação de mídia do WA Keeper em Acessibilidade",
                consumesAttempt = false
            )
        }

        synchronized(this) {
            if (pending != null) {
                return ReplyResult.Rejected(
                    "há outro anexo sendo despachado agora",
                    consumesAttempt = false
                )
            }
        }

        val result = CompletableDeferred<ReplyResult>()
        val job = Pending(packageName, sender, result)
        synchronized(this) { pending = job }

        val started = runCatching {
            val uri = Uri.parse(uriText)
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                setPackage(packageName)
                putExtra(Intent.EXTRA_STREAM, uri)
                if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                clipData = ClipData.newRawUri("WA Keeper attachment", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess

        if (!started) {
            clear(job)
            return ReplyResult.Rejected(
                "não foi possível abrir o compartilhamento do WhatsApp",
                consumesAttempt = false
            )
        }

        val outcome = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
        if (outcome != null) return outcome

        clear(job)
        return ReplyResult.Rejected(
            "o compartilhamento de mídia não concluiu em 90 segundos",
            consumesAttempt = false
        )
    }

    fun current(): Pending? = pending

    fun contactSelected(job: Pending) {
        if (pending === job) job.phase = Phase.SEND
    }

    fun complete(job: Pending) {
        if (pending !== job) return
        job.result.complete(ReplyResult.Accepted)
        clear(job)
    }

    fun fail(job: Pending, reason: String) {
        if (pending !== job) return
        job.result.complete(ReplyResult.Rejected(reason, consumesAttempt = false))
        clear(job)
    }

    private fun clear(job: Pending) {
        synchronized(this) {
            if (pending === job) pending = null
        }
    }
}

class MediaShareAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val job = MediaShareAutomation.current() ?: return
        if (event?.packageName?.toString() != job.packageName) return
        val root = rootInActiveWindow ?: return

        when (job.phase) {
            MediaShareAutomation.Phase.PICK_CONTACT -> pickContact(root, job)
            MediaShareAutomation.Phase.SEND -> clickSend(root, job)
        }
    }

    private fun pickContact(root: AccessibilityNodeInfo, job: MediaShareAutomation.Pending) {
        val candidates = root.findAccessibilityNodeInfosByText(job.sender)
            .mapNotNull { exactClickableAncestor(it, job.sender) }
            .distinctBy { System.identityHashCode(it) }

        when {
            candidates.size == 1 -> {
                if (candidates.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    MediaShareAutomation.contactSelected(job)
                }
            }
            candidates.size > 1 -> MediaShareAutomation.fail(
                job,
                "há mais de um contato com esse nome; envio de mídia cancelado por segurança"
            )
        }
    }

    private fun clickSend(root: AccessibilityNodeInfo, job: MediaShareAutomation.Pending) {
        val labels = listOf("Enviar", "Send", "Enviar mensagem", "Send message")
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            val exact = nodes.firstOrNull { node ->
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                (text.equals(label, true) || desc.equals(label, true)) && clickable(node) != null
            } ?: continue
            val button = clickable(exact) ?: continue
            if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                MediaShareAutomation.complete(job)
                return
            }
        }

        val button = findByDescription(root, labels)
        if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            MediaShareAutomation.complete(job)
        }
    }

    private fun exactClickableAncestor(node: AccessibilityNodeInfo, expected: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (!text.equals(expected, true) && !desc.equals(expected, true)) return null
        return clickable(node)
    }

    private fun clickable(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        repeat(5) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    private fun findByDescription(
        node: AccessibilityNodeInfo,
        labels: List<String>
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.trim()
        if (desc != null && labels.any { it.equals(desc, true) } && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findByDescription(it, labels) }
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() = Unit
}
