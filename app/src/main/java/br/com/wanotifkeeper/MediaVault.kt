package br.com.wanotifkeeper

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Copia para o armazenamento privado do app o áudio de voz que a pessoa acabou de
 * enviar — antes que o WhatsApp o apague num "apagar para todos".
 *
 * O áudio NÃO vem na notificação (só o rótulo "🎤 Mensagem de voz"); o WhatsApp
 * grava o arquivo `.opus` em `Android/media/<pkg>/…/WhatsApp Voice Notes/<ano+semana>/`.
 * Essa pasta tem `.nomedia`, então o MediaStore não a indexa: para lê-la é preciso
 * a permissão "Acesso a todos os arquivos" (MANAGE_EXTERNAL_STORAGE).
 *
 * A captura é disparada pela notificação de voz recebida (ver NotifListenerService),
 * então pegamos o arquivo mais novo próximo do horário da notificação — o que naturalmente
 * seleciona o áudio RECEBIDO, não os que você mesmo enviou em outro momento.
 */
object MediaVault {

    /** Anchor de mensagem de voz nas notificações (pt/es/en). O 🎤 é o sinal mais confiável. */
    private val VOICE_HINT = Regex(
        "🎤|🎙|voice message|mensagem de voz|mensaje de voz",
        RegexOption.IGNORE_CASE
    )

    /** Arquivos-fonte já copiados nesta sessão, para não anexar o mesmo áudio duas vezes. */
    private val consumed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun looksLikeVoiceMessage(text: String): Boolean = VOICE_HINT.containsMatchIn(text)

    /** Antes de 11 o acesso a arquivo era direto; de 11 em diante exige All Files Access. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun audioDir(ctx: Context): File =
        File(ctx.filesDir, "notif-audio").apply { mkdirs() }

    private fun voiceRoot(pkg: String): File? {
        val base = Environment.getExternalStorageDirectory()
        return when (pkg) {
            Prefs.PKG_WHATSAPP ->
                File(base, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes")
            Prefs.PKG_BUSINESS ->
                File(base, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes")
            else -> null
        }
    }

    /**
     * Procura o `.opus` recebido próximo de [aroundTs] e o copia para [audioDir].
     * Retorna o caminho local, ou null se nada elegível foi encontrado (ainda).
     */
    fun captureLatest(ctx: Context, pkg: String, aroundTs: Long): String? {
        if (!hasAllFilesAccess()) return null
        val root = voiceRoot(pkg)?.takeIf { it.isDirectory } ?: return null

        val cutoff = aroundTs - WINDOW_BACK_MS
        // Áudios vão para a pasta da semana atual; olhar as 2 mais recentes basta e é barato.
        val weekDirs = root.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name }
            ?.take(2)
            ?: return null

        val candidate = weekDirs
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".opus") }?.toList() ?: emptyList() }
            .filter { it.lastModified() >= cutoff && key(it) !in consumed }
            .maxByOrNull { it.lastModified() }
            ?: return null

        return runCatching {
            val dest = File(audioDir(ctx), candidate.name)
            candidate.copyTo(dest, overwrite = true)
            consumed.add(key(candidate))
            dest.absolutePath
        }.getOrNull()
    }

    private fun key(f: File): String = "${f.name}:${f.lastModified()}"

    /** Janela para trás a partir da notificação — o arquivo aparece por volta desse horário. */
    private const val WINDOW_BACK_MS = 20_000L
}
