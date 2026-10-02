package br.com.wanotifkeeper.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.Instant

class BenchmarkRunnerService : Service() {
    companion object {
        const val ACTION_START = "br.com.wanotifkeeper.benchmark.START"
        const val EXTRA_REPEATS = "repeats"
        const val EXTRA_COOLDOWN_SECONDS = "cooldownSeconds"
        const val EXTRA_MODEL = "model"
        const val CHANNEL_ID = "asr-benchmark"
        const val NOTIFICATION_ID = 24031
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WA-Keeper-Benchmark::Runner")
        wakeLock.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_STICKY
        if (runningJob?.isActive == true) return START_STICKY

        val repeats = intent.getIntExtra(EXTRA_REPEATS, 3).coerceIn(1, 10)
        val cooldownSeconds = intent.getIntExtra(EXTRA_COOLDOWN_SECONDS, 120).coerceIn(0, 3600)
        val model = intent.getStringExtra(EXTRA_MODEL).orEmpty().ifBlank { "small" }

        startForeground(
            NOTIFICATION_ID,
            notification("Benchmark preparado", "Executando em segundo plano")
        )

        runningJob = scope.launch {
            if (!wakeLock.isHeld) wakeLock.acquire()
            try {
                runBenchmark(repeats, cooldownSeconds, model)
            } catch (error: Throwable) {
                val root = File(filesDir, "benchmark").apply { mkdirs() }
                writeStatus(root, "failed", error.message ?: error.javaClass.simpleName, null)
                updateNotification("Benchmark falhou", error.message ?: error.javaClass.simpleName)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runBenchmark(repeats: Int, cooldownSeconds: Int, model: String) {
        val root = File(filesDir, "benchmark").apply { mkdirs() }
        val inputDir = File(root, "input")
        val original = File(inputDir, "original.wav")
        val transformed = File(inputDir, "silence_speed115.wav")

        check(original.isFile && original.length() > 0L) { "original.wav ausente" }
        check(transformed.isFile && transformed.length() > 0L) { "silence_speed115.wav ausente" }

        val plan = ensurePlan(root, repeats)
        val runsFile = File(root, "runs.tsv")
        if (!runsFile.exists()) {
            runsFile.writeText(
                "rodada\tordem\tvariante\tduracao_s\telapsed_ms\tbattery_temp_before_c\tbattery_temp_after_c\tthermal_before\tthermal_after\tstarted_at\tfinished_at\ttranscript\n",
                Charsets.UTF_8
            )
        }

        val completed = completedKeys(runsFile)
        writeStatus(root, "running", "benchmark em andamento", null)

        for ((index, item) in plan.withIndex()) {
            val key = item.round.toString() + ":" + item.order + ":" + item.variant
            if (key in completed) continue

            updateNotification(
                "Rodada " + item.round + "/" + repeats,
                item.variant + " (" + (index + 1) + "/" + plan.size + ")"
            )

            if (index > 0 && cooldownSeconds > 0) {
                writeStatus(root, "cooldown", "aguardando " + cooldownSeconds + "s", item)
                delay(cooldownSeconds * 1000L)
            }

            val input = when (item.variant) {
                "original" -> original
                "silence_speed115" -> transformed
                else -> error("variante desconhecida: " + item.variant)
            }

            val duration = inputDurationSeconds(input)
            val tempBefore = batteryTempC()
            val thermalBefore = thermalStatus()
            val startedAt = Instant.now().toString()

            writeStatus(root, "transcribing", "transcrevendo " + item.variant, item)
            val result = BenchmarkTranscriber.transcribe(filesDir, input, model)
            val finishedAt = Instant.now().toString()
            val tempAfter = batteryTempC()
            val thermalAfter = thermalStatus()

            val transcriptFile = File(
                root,
                "transcripts/round-" + item.round + "-" + item.order + "-" + item.variant + ".txt"
            ).apply { parentFile?.mkdirs() }
            transcriptFile.writeText(result.text + "\n", Charsets.UTF_8)

            runsFile.appendText(
                listOf(
                    item.round,
                    item.order,
                    item.variant,
                    "%.3f".format(duration),
                    result.elapsedMs,
                    tempBefore?.let { "%.1f".format(it) }.orEmpty(),
                    tempAfter?.let { "%.1f".format(it) }.orEmpty(),
                    thermalBefore,
                    thermalAfter,
                    startedAt,
                    finishedAt,
                    transcriptFile.relativeTo(root).path
                ).joinToString("\t") + "\n",
                Charsets.UTF_8
            )
        }

        writeStatus(root, "completed", "benchmark concluído", null)
        updateNotification("Benchmark concluído", "Resultados prontos para coleta")
    }

    private data class PlanItem(val round: Int, val order: Int, val variant: String)

    private fun ensurePlan(root: File, repeats: Int): List<PlanItem> {
        val planFile = File(root, "plan.tsv")
        if (planFile.isFile && planFile.length() > 0L) {
            return planFile.readLines().drop(1).mapNotNull { line ->
                val p = line.split('\t')
                if (p.size == 3) PlanItem(p[0].toInt(), p[1].toInt(), p[2]) else null
            }
        }

        val items = mutableListOf<PlanItem>()
        repeat(repeats) { r ->
            val variants = listOf("original", "silence_speed115").shuffled()
            variants.forEachIndexed { index, variant ->
                items += PlanItem(r + 1, index + 1, variant)
            }
        }

        planFile.writeText(
            "rodada\tordem\tvariante\n" +
                items.joinToString("\n") {
                    it.round.toString() + "\t" + it.order + "\t" + it.variant
                } + "\n",
            Charsets.UTF_8
        )
        return items
    }

    private fun completedKeys(runsFile: File): Set<String> =
        if (!runsFile.isFile) emptySet()
        else runsFile.readLines().drop(1).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size >= 3) p[0] + ":" + p[1] + ":" + p[2] else null
        }.toSet()

    private fun writeStatus(root: File, state: String, message: String, item: PlanItem?) {
        val json = JSONObject()
            .put("state", state)
            .put("message", message)
            .put("updatedAt", Instant.now().toString())

        if (item != null) {
            json.put("round", item.round)
            json.put("order", item.order)
            json.put("variant", item.variant)
        }
        File(root, "status.json").writeText(json.toString(2) + "\n", Charsets.UTF_8)
    }

    private fun batteryTempC(): Double? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (raw == Int.MIN_VALUE) null else raw / 10.0
    }

    private fun thermalStatus(): Int {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    private fun inputDurationSeconds(file: File): Double {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var maxUs = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val value = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    format.getLong(android.media.MediaFormat.KEY_DURATION)
                } else 0L
                if (value > maxUs) maxUs = value
            }
            maxUs / 1_000_000.0
        } finally {
            extractor.release()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ASR Benchmark", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(title, text))
    }
}
