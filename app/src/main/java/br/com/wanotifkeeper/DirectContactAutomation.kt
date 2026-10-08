package br.com.wanotifkeeper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

object ContactPhone {
    fun normalizeForWhatsApp(raw: String): String {
        val trimmed = raw.trim()
        var digits = trimmed.filter(Char::isDigit)

        // Prefixos internacionais explícitos já trazem o código do país.
        // Depois de remover "00", não podemos aplicar a heurística brasileira
        // baseada apenas no comprimento: um uruguaio, por exemplo, pode ter
        // 10/11 dígitos e acabaria ganhando "55" indevidamente.
        if (trimmed.startsWith("00")) return digits.drop(2)
        if (trimmed.startsWith("+")) return digits

        return when {
            digits.startsWith("55") && digits.length in 12..13 -> digits
            digits.length == 10 || digits.length == 11 -> "55$digits"
            else -> digits
        }
    }

    fun candidatesForDisplayName(context: Context, displayName: String): List<String> {
        ContactDirectory.candidates(displayName).takeIf { it.isNotEmpty() }?.let { return it }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val phones = linkedSetOf<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?",
            arrayOf(displayName),
            null
        )?.use { cursor ->
            val nameIx = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val phoneIx = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIx)?.trim().orEmpty()
                if (!name.equals(displayName.trim(), ignoreCase = true)) continue
                val normalized = normalizeForWhatsApp(cursor.getString(phoneIx).orEmpty())
                if (normalized.length >= 10) phones += normalized
            }
        }

        return phones.toList()
    }

    /**
     * Atalho para fluxos que realmente exigem um único telefone.
     * Conversas agendadas não dependem disso; grupos nunca passam por aqui.
     */
    fun resolveUniqueForDisplayName(context: Context, displayName: String): String? =
        candidatesForDisplayName(context, displayName).singleOrNull()
}

object DirectContactAutomation {
    private const val TIMEOUT_MS = 90_000L

    data class Pending(
        val packageName: String,
        val phone: String,
        val text: String,
        val result: CompletableDeferred<ReplyResult>,
        @Volatile var textFilled: Boolean = false
    )

    @Volatile private var pending: Pending? = null

    suspend fun send(
        context: Context,
        packageName: String,
        phone: String,
        text: String
    ): ReplyResult {
        val lease = AutomatedSendGate.acquire() ?: return AutomatedSendGate.busyResult()
        try {
        if (!MediaShareAutomation.isEnabled(context)) {
            return ReplyResult.Rejected(
                "ative a automação de mídia do WA Keeper em Acessibilidade",
                consumesAttempt = false
            )
        }
        if (BankMode.isEnabled(context)) {
            return ReplyResult.Rejected("Modo Banco ativo", consumesAttempt = false)
        }

        val normalized = ContactPhone.normalizeForWhatsApp(phone)
        if (normalized.length < 10) {
            return ReplyResult.Rejected("telefone inválido para WhatsApp", consumesAttempt = true)
        }

        synchronized(this) {
            if (pending != null || MediaShareAutomation.current() != null) {
                return ReplyResult.Rejected(
                    "há outra automação de envio em andamento",
                    consumesAttempt = false
                )
            }
        }

        val result = CompletableDeferred<ReplyResult>()
        val job = Pending(packageName, normalized, text, result)
        synchronized(this) { pending = job }

        val started = runCatching {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/$normalized?text=$encoded")
            ).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess

        if (!started) {
            clear(job)
            return ReplyResult.Rejected("não foi possível abrir o contato no WhatsApp")
        }

        val outcome = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
        if (outcome != null) return outcome

        clear(job)
        return ReplyResult.Rejected(
            "o envio para o contato não concluiu em 90 segundos",
            consumesAttempt = false
        )
        } finally {
            lease.release()
        }
    }

    fun current(): Pending? = pending

    fun cancelForUserInteraction() {
        val job = pending ?: return
        job.result.complete(
            ReplyResult.Rejected(
                AutomatedSendGate.USER_BUSY,
                consumesAttempt = false
            )
        )
        clear(job)
    }

    fun handle(root: AccessibilityNodeInfo, eventPackage: String?) {
        val job = pending ?: return
        if (eventPackage != job.packageName) return

        if (!job.textFilled) {
            val editable = findEditable(root)
            if (editable != null) {
                val current = editable.text?.toString().orEmpty()
                if (current.isBlank() && job.text.isNotBlank()) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            job.text
                        )
                    }
                    editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                job.textFilled = true
            }
        }

        val send = findSend(root)
        if (send != null && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            job.result.complete(ReplyResult.Accepted)
            clear(job)
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let(::findEditable)
            if (found != null) return found
        }
        return null
    }

    private fun findSend(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val labels = listOf("Enviar", "Send", "Enviar mensagem", "Send message")
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        if (node.isClickable && labels.any { it.equals(text, true) || it.equals(desc, true) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let(::findSend)
            if (found != null) return found
        }
        return null
    }

    private fun clear(job: Pending) {
        synchronized(this) {
            if (pending === job) pending = null
        }
    }
}
