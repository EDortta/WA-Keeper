package br.com.wanotifkeeper

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Retenção padrão: o WhatsApp só deixa apagar-para-todos dentro de ~2 dias.
 * Fora dessa janela a mensagem não some mais, então guardá-la aqui não agrega.
 * Margem de 3× sobre a janela → 6 dias. Conversas com config própria escapam disso.
 */
object RetentionPolicy {
    val DELETE_WINDOW_MS: Long = TimeUnit.DAYS.toMillis(2)
    val DEFAULT_WINDOW_MS: Long = DELETE_WINDOW_MS * 3

    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    const val WEEK_MS = 604_800_000L
}

object Retention {

    /**
     * Aplica retenção + limpeza de ruído legado. Idempotente; roda em background.
     * Retorna quantas linhas foram removidas.
     */
    suspend fun purge(ctx: Context, now: Long): Int {
        val db = NotifDatabase.get(ctx)
        val dao = db.dao()
        val before = dao.count()

        // 1. Ruído já gravado antes do filtro existir (backup, "mensagem apagada", etc.)
        val noiseIds = dao.getAll()
            .filter { NoiseFilter.isNoise(it.sender, it.text) }
            .map { it.id }
        if (noiseIds.isNotEmpty()) noiseIds.chunked(400).forEach { dao.deleteByIds(it) }

        // 2. Retenção por conversa configurada
        val configured = db.settings().getAll()
        for (s in configured) {
            when (s.retentionMode) {
                RetentionMode.FOREVER -> Unit
                RetentionMode.NEVER -> dao.deleteSender(s.sender)
                RetentionMode.CUSTOM -> dao.purgeSender(s.sender, now - s.durationMillis)
            }
        }

        // 3. Retenção padrão para todo o resto.
        //    Sentinela "" evita `NOT IN ()`, que o SQLite rejeita.
        dao.purgeDefault(
            cutoff = now - RetentionPolicy.DEFAULT_WINDOW_MS,
            excluded = configured.map { it.sender } + ""
        )

        sweepOrphanImages(ctx, dao.allImagePaths().toSet())
        sweepOrphanAudio(ctx, dao.allAudioPaths().toSet())
        return before - dao.count()
    }

    /** Apaga arquivos de imagem que não são mais referenciados por nenhuma linha. */
    private fun sweepOrphanImages(ctx: Context, referenced: Set<String>) {
        val dir = imageDir(ctx)
        dir.listFiles()?.forEach { f ->
            if (f.absolutePath !in referenced) f.delete()
        }
    }

    /** Apaga áudios copiados que não são mais referenciados por nenhuma linha. */
    private fun sweepOrphanAudio(ctx: Context, referenced: Set<String>) {
        MediaVault.audioDir(ctx).listFiles()?.forEach { f ->
            if (f.absolutePath !in referenced) f.delete()
        }
    }

    fun imageDir(ctx: Context): File =
        File(ctx.filesDir, "notif-images").apply { mkdirs() }
}
