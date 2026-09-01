package br.com.wanotifkeeper

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Copia para o armazenamento privado do app mídias recebidas que o WhatsApp grava
 * fora da notificação. Hoje cobre voice notes e imagens.
 */
object MediaVault {

    /**
     * Arquivos-fonte já copiados nesta sessão, para não anexar a mesma mídia duas vezes.
     * Vive só em memória, como sempre viveu para o áudio: se o listener for morto e
     * recriado, o set volta vazio. O dano é limitado pela janela de tempo, que é medida a
     * partir do `postTime` da notificação NOVA.
     */
    private val consumed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Discriminador para nomes de destino, para duas notificações não colidirem no mesmo arquivo. */
    private val destSeq = AtomicLong(0)

    /**
     * Locks SEPARADOS. `@Synchronized` num `object` usa o monitor do próprio MediaVault, então
     * áudio e imagem disputariam o mesmo lock: a varredura de imagem (listFiles em várias
     * pastas + copyTo de vários MB, até 4 vezes em 10,5 s) bloquearia a captura de áudio —
     * desfazendo, no nível do lock, o desacoplamento feito no nível da coroutine. As duas
     * exclusões não protegem nada em comum: as chaves de `consumed` vivem em árvores disjuntas.
     */
    private val voiceLock = Any()
    private val imageLock = Any()

    /** Cópias já feitas a partir de um URI de notificação, para não copiar a mesma imagem de novo. */
    private val uriCopies = ConcurrentHashMap<String, String>()

    // A regra de "isto parece mídia?" vive em MediaHints, sem Android, para ter teste.
    fun looksLikeVoiceMessage(text: String): Boolean = MediaHints.looksLikeVoiceMessage(text)

    fun looksLikeImageMessage(text: String): Boolean = MediaHints.looksLikeImageMessage(text)

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
    fun captureLatest(ctx: Context, pkg: String, aroundTs: Long): String? = synchronized(voiceLock) {
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
    fun captureImageUri(ctx: Context, uri: Uri, mimeType: String?, aroundTs: Long): String? = synchronized(imageLock) {
      runCatching {
        // A #19 pede dedup pelo arquivo-fonte. Aqui a fonte é o URI: se a mesma notificação
        // for repostada, reaproveitamos a cópia em vez de gerar um arquivo novo por vez.
        uriCopies[uri.toString()]?.let { if (File(it).length() > 0L) return@runCatching it }
        val ext = when {
            mimeType.equals("image/jpeg", ignoreCase = true) -> ".jpg"
            mimeType.equals("image/png", ignoreCase = true) -> ".png"
            mimeType.equals("image/webp", ignoreCase = true) -> ".webp"
            mimeType.equals("image/heic", ignoreCase = true) -> ".heic"
            else -> ".img"
        }
        // O sufixo de sequência existe porque duas notificações podem ter o MESMO postTime
        // (o resumo do grupo e a filha, ou uma atualização da mesma notificação). Sem ele,
        // duas linhas apontariam para o mesmo arquivo e uma ficaria com a imagem da outra.
        val dest = File(Retention.imageDir(ctx), "img-uri-$aroundTs-${destSeq.incrementAndGet()}$ext")
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        try {
            input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } catch (t: Throwable) {
            dest.delete()   // cópia parcial não vira lixo esperando a retenção
            throw t
        }
        // Stream vazio (URI já revogado, mídia não baixada) gravaria um arquivo de 0 byte que
        // conta como sucesso, trava o fallback de diretório (que só roda com imagePath nulo) e
        // some sem aviso na tela de detalhe. Melhor não ter imagem do que ter um arquivo morto.
        if (dest.length() == 0L) {
            dest.delete()
            return@runCatching null
        }
        uriCopies[uri.toString()] = dest.absolutePath
        dest.absolutePath
      }.getOrNull()
    }

    /**
     * Fallback para WhatsApp que não coloca bitmap/URI utilizável na notificação.
     * Só deve ser chamado quando a notificação tiver indício de foto/imagem.
     */
    fun captureLatestImage(ctx: Context, pkg: String, aroundTs: Long): String? = synchronized(imageLock) {
        if (!hasAllFilesAccess()) return null
        val root = imageRoot(pkg)?.takeIf { it.isDirectory } ?: return null
        val cutoff = aroundTs - IMAGE_WINDOW_BACK_MS
        val ceiling = aroundTs + IMAGE_WINDOW_FORWARD_MS

        // Não sabemos (sem o aparelho) se esta versão do WhatsApp grava as imagens direto na
        // raiz ou dentro de uma subpasta de semana, como faz com as notas de voz. Varrer os
        // dois é barato e correto nos dois casos — melhor que supor um deles.
        //
        // Só subpastas com NOME NUMÉRICO (ano+semana). "WhatsApp Images" também tem `Sent/` e
        // `Private/`: varrer "qualquer diretório" faria uma imagem que VOCÊ acabou de enviar
        // competir com a recebida e, por estar mais perto no tempo, vencer o desempate.
        val dirs = buildList {
            add(root)
            root.listFiles { f -> f.isDirectory && WEEK_DIR.matches(f.name) }
                ?.sortedByDescending { it.name }
                ?.take(2)
                ?.let(::addAll)
        }

        // Um arquivo cujo mtime é de agora mesmo provavelmente ainda está sendo baixado; copiá-lo
        // produz uma imagem truncada, e como o retry para no primeiro sucesso ninguém corrige
        // depois. Exigimos que ele esteja parado há FILE_SETTLE_MS.
        val settled = System.currentTimeMillis() - FILE_SETTLE_MS

        val candidate = dirs
            .flatMap { dir -> dir.listFiles { f -> f.isFile && isImageFile(f) }?.toList() ?: emptyList() }
            .filter { it.lastModified() in cutoff..ceiling && it.lastModified() <= settled && key(it) !in consumed }
            .minByOrNull { kotlin.math.abs(it.lastModified() - aroundTs) }
            ?: return null

        return runCatching {
            val dest = File(Retention.imageDir(ctx), "img-$aroundTs-${candidate.name}")
            candidate.copyTo(dest, overwrite = true)
            consumed.add(key(candidate))
            dest.absolutePath
        }.getOrNull()
    }

    /** Subpasta de semana do WhatsApp (ex.: `202635`). Exclui `Sent/` e `Private/`. */
    private val WEEK_DIR = Regex("^\\d{4,8}$")

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    private fun isImageFile(f: File): Boolean = f.extension.lowercase() in IMAGE_EXTENSIONS

    private fun key(f: File): String = "${f.absolutePath}:${f.lastModified()}"

    /** Janela para trás a partir da notificação — o arquivo aparece por volta desse horário. */
    private const val WINDOW_BACK_MS = 20_000L
    private const val IMAGE_WINDOW_BACK_MS = 15_000L

    /**
     * Janela adiante: tem que cobrir o retry inteiro do NotifListenerService, que soma
     * 300+1200+3000+6000 = 10,5 s depois do postTime. Com os 8 s de antes, um arquivo que
     * aterrissasse em t+9 s era procurado pelo último retry e recusado pela janela.
     */
    private const val IMAGE_WINDOW_FORWARD_MS = 12_000L

    /** Quanto tempo um arquivo precisa estar sem escrita para ser considerado baixado por inteiro. */
    private const val FILE_SETTLE_MS = 1_500L
}
