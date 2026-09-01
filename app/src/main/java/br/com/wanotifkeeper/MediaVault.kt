package br.com.wanotifkeeper

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Copia para o armazenamento privado do app mídias recebidas que o WhatsApp grava
 * fora da notificação. Hoje cobre voice notes e imagens.
 */
object MediaVault {

    /** Anchor de mensagem de voz nas notificações (pt/es/en). O 🎤 é o sinal mais confiável. */
    private val VOICE_HINT = Regex(
        "🎤|🎙|voice message|mensagem de voz|mensaje de voz",
        RegexOption.IGNORE_CASE
    )

    /** Anchor de imagem nas notificações (pt/es/en). */
    private val IMAGE_HINT = Regex(
        "📷|🖼|photo|picture|image|foto|imagem|fotografia",
        RegexOption.IGNORE_CASE
    )

    /** Arquivos-fonte já copiados nesta sessão, para não anexar a mesma mídia duas vezes. */
    private val consumed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun looksLikeVoiceMessage(text: String): Boolean = VOICE_HINT.containsMatchIn(text)

    fun looksLikeImageMessage(text: String): Boolean = IMAGE_HINT.containsMatchIn(text)

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

    private fun imageRoot(pkg: String): File? {
        val base = Environment.getExternalStorageDirectory()
        return when (pkg) {
            Prefs.PKG_WHATSAPP ->
                File(base, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images")
            Prefs.PKG_BUSINESS ->
                File(base, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images")
            else -> null
        }
    }

    /**
     * Procura o `.opus` recebido próximo de [aroundTs] e o copia para [audioDir].
     * Retorna o caminho local, ou null se nada elegível foi encontrado (ainda).
     */
    @Synchronized
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

    /**
     * Copia uma imagem referenciada diretamente pela notificação para o armazenamento privado.
     * O URI pode deixar de ser válido quando a notificação some, então a cópia é imediata.
     */
    fun captureImageUri(ctx: Context, uri: Uri, mimeType: String?, aroundTs: Long): String? = runCatching {
        val ext = when {
            mimeType.equals("image/jpeg", ignoreCase = true) -> ".jpg"
            mimeType.equals("image/png", ignoreCase = true) -> ".png"
            mimeType.equals("image/webp", ignoreCase = true) -> ".webp"
            mimeType.equals("image/heic", ignoreCase = true) -> ".heic"
            else -> ".img"
        }
        val dest = File(Retention.imageDir(ctx), "img-uri-$aroundTs$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        dest.absolutePath
    }.getOrNull()

    /**
     * Fallback para WhatsApp que não coloca bitmap/URI utilizável na notificação.
     * Só deve ser chamado quando a notificação tiver indício de foto/imagem.
     */
    @Synchronized
    fun captureLatestImage(ctx: Context, pkg: String, aroundTs: Long): String? {
        if (!hasAllFilesAccess()) return null
        val root = imageRoot(pkg)?.takeIf { it.isDirectory } ?: return null
        val cutoff = aroundTs - IMAGE_WINDOW_BACK_MS

        val candidate = root.listFiles { f ->
            f.isFile &&
                isImageFile(f) &&
                f.lastModified() >= cutoff &&
                f.lastModified() <= aroundTs + IMAGE_WINDOW_FORWARD_MS &&
                key(f) !in consumed
        }?.minByOrNull { kotlin.math.abs(it.lastModified() - aroundTs) }
            ?: return null

        return runCatching {
            val dest = File(Retention.imageDir(ctx), "img-$aroundTs-${candidate.name}")
            candidate.copyTo(dest, overwrite = true)
            consumed.add(key(candidate))
            dest.absolutePath
        }.getOrNull()
    }

    private fun isImageFile(f: File): Boolean {
        val ext = f.extension.lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    }

    private fun key(f: File): String = "${f.absolutePath}:${f.lastModified()}"

    /** Janela para trás a partir da notificação — o arquivo aparece por volta desse horário. */
    private const val WINDOW_BACK_MS = 20_000L
    private const val IMAGE_WINDOW_BACK_MS = 15_000L
    private const val IMAGE_WINDOW_FORWARD_MS = 8_000L
}
