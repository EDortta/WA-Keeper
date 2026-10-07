package br.com.wanotifkeeper.godofredo.benchmark

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

class GodofredoBenchmarkActivity : Activity() {
    companion object {
        private const val AUDIO_PERMISSION_REQUEST = 4701
    }

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var ratingContainer: LinearLayout

    private var recognizer: SpeechRecognizer? = null
    private var sessionId: String? = null
    private var sessionStartedElapsed = 0L
    private var readyElapsed = 0L
    private var speechStartElapsed = 0L
    private var sequence = 0L
    private var pendingRatingSessionId: String? = null
    private var pendingRatingTranscript: String? = null

    private val evidenceDir by lazy {
        File(filesDir, "godofredo-benchmark").apply { mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        logEvent("app_open", JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL))
        updateStatus("Pronto. Toque em OUVIR e diga uma instrução completa.")
    }

    override fun onDestroy() {
        destroyRecognizer("activity_destroy")
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
        }
        val title = TextView(this).apply {
            text = "Godofredo Benchmark\n\nFase 1: instrução → transcrição"
            textSize = 22f
        }
        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, 32, 0, 24)
        }
        transcript = TextView(this).apply {
            textSize = 20f
            setPadding(0, 24, 0, 32)
        }
        startButton = Button(this).apply {
            text = "OUVIR"
            setOnClickListener { ensurePermissionAndStart() }
        }
        stopButton = Button(this).apply {
            text = "PARAR"
            isEnabled = false
            setOnClickListener { stopListening("user_stop") }
        }
        ratingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        val ratingTitle = TextView(this).apply {
            text = "Qualidade da transcrição:"
            textSize = 18f
            setPadding(0, 24, 0, 8)
        }
        ratingContainer.addView(ratingTitle)
        listOf("Incompreensível", "Aceitável", "Boa", "Excelente").forEach { label ->
            ratingContainer.addView(Button(this).apply {
                text = label
                setOnClickListener { recordRating(label) }
            })
        }

        root.addView(title)
        root.addView(status)
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(transcript)
        root.addView(ratingContainer)
        return ScrollView(this).apply { addView(root) }
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != AUDIO_PERMISSION_REQUEST) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        logEvent("permission_result", JSONObject().put("granted", granted))
        if (granted) startListening() else updateStatus("Microfone não autorizado.")
    }

    private fun startListening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateStatus("Android " + Build.VERSION.SDK_INT + ": reconhecimento on-device exige API 31+.")
            logEvent("unsupported_sdk", JSONObject().put("sdk", Build.VERSION.SDK_INT))
            return
        }

        destroyRecognizer("replace_before_start")
        sessionId = UUID.randomUUID().toString()
        sessionStartedElapsed = SystemClock.elapsedRealtime()
        readyElapsed = 0L
        speechStartElapsed = 0L
        transcript.text = ""
        ratingContainer.visibility = android.view.View.GONE
        pendingRatingSessionId = null
        pendingRatingTranscript = null
        startButton.isEnabled = false
        stopButton.isEnabled = true

        val onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        logEvent("session_start", JSONObject()
            .put("sessionId", sessionId)
            .put("onDeviceAvailableReported", onDeviceAvailable)
            .put("language", "pt-BR")
            .put("mode", "manual_single_turn"))

        recognizer = runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }.onFailure {
            logEvent("recognizer_create_failed", JSONObject()
                .put("sessionId", sessionId)
                .put("errorClass", it.javaClass.simpleName)
                .put("message", it.message ?: ""))
        }.getOrNull()

        val activeRecognizer = recognizer
        if (activeRecognizer == null) {
            finishSessionUi("Falha ao criar reconhecedor on-device.")
            return
        }

        activeRecognizer.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        updateStatus("Abrindo microfone...")
        runCatching { activeRecognizer.startListening(intent) }
            .onSuccess {
                logEvent("start_listening_ok", JSONObject().put("sessionId", sessionId))
            }
            .onFailure {
                logEvent("start_listening_failed", JSONObject()
                    .put("sessionId", sessionId)
                    .put("errorClass", it.javaClass.simpleName)
                    .put("message", it.message ?: ""))
                finishSessionUi("startListening falhou: " + it.javaClass.simpleName)
            }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            readyElapsed = SystemClock.elapsedRealtime()
            logEvent("ready_for_speech", timingJson())
            updateStatus("Microfone pronto. Fale agora.")
        }

        override fun onBeginningOfSpeech() {
            speechStartElapsed = SystemClock.elapsedRealtime()
            logEvent("beginning_of_speech", timingJson())
            updateStatus("Fala detectada...")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (sequence % 8L == 0L) {
                logEvent("rms_sample", timingJson().put("rmsDb", rmsdB))
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            logEvent("audio_buffer", timingJson().put("bytes", buffer?.size ?: 0))
        }

        override fun onEndOfSpeech() {
            logEvent("end_of_speech", timingJson())
            updateStatus("Processando...")
        }

        override fun onError(error: Int) {
            logEvent("recognition_error", timingJson()
                .put("errorCode", error)
                .put("errorName", errorName(error)))
            finishSessionUi("Erro " + errorName(error) + " (" + error + ")")
        }

        override fun onResults(results: Bundle?) {
            val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val best = alternatives.firstOrNull()
            val completedSessionId = sessionId
            logEvent("final_results", timingJson()
                .put("alternatives", JSONArray(alternatives))
                .put("confidenceScores", JSONArray(confidences?.toList() ?: emptyList<Float>())))
            transcript.text = best?.let { "Transcrição:\n" + it } ?: "Sem transcrição."
            if (!best.isNullOrBlank() && completedSessionId != null) {
                pendingRatingSessionId = completedSessionId
                pendingRatingTranscript = best
                ratingContainer.visibility = android.view.View.VISIBLE
            }
            finishSessionUi("Concluído.")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val alternatives = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            logEvent("partial_results", timingJson().put("alternatives", JSONArray(alternatives)))
            alternatives.firstOrNull()?.let { transcript.text = "Parcial:\n" + it }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            logEvent("recognizer_event", timingJson().put("eventType", eventType))
        }
    }

    private fun recordRating(label: String) {
        val ratedSessionId = pendingRatingSessionId ?: return
        val ratedTranscript = pendingRatingTranscript ?: return
        logEvent("transcript_rating", JSONObject()
            .put("sessionId", ratedSessionId)
            .put("rating", label)
            .put("transcript", ratedTranscript))
        ratingContainer.visibility = android.view.View.GONE
        pendingRatingSessionId = null
        pendingRatingTranscript = null
        updateStatus("Classificação registrada: " + label)
    }

    private fun stopListening(reason: String) {
        logEvent("stop_requested", timingJson().put("reason", reason))
        runCatching { recognizer?.stopListening() }
        finishSessionUi("Parado pelo usuário.")
    }

    private fun finishSessionUi(message: String) {
        logEvent("session_end", timingJson().put("message", message))
        startButton.isEnabled = true
        stopButton.isEnabled = false
        updateStatus(message)
        destroyRecognizer("session_end")
        sessionId = null
    }

    private fun destroyRecognizer(reason: String) {
        recognizer?.let {
            logEvent("recognizer_destroy", timingJson().put("reason", reason))
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private fun timingJson(): JSONObject {
        val now = SystemClock.elapsedRealtime()
        return JSONObject()
            .put("sessionId", sessionId)
            .put("elapsedFromStartMs", if (sessionStartedElapsed > 0L) now - sessionStartedElapsed else -1L)
            .put("readyLatencyMs", if (readyElapsed > 0L) readyElapsed - sessionStartedElapsed else -1L)
            .put("speechStartLatencyMs", if (speechStartElapsed > 0L) speechStartElapsed - sessionStartedElapsed else -1L)
    }

    @Synchronized
    private fun logEvent(type: String, details: JSONObject = JSONObject()) {
        sequence += 1
        val event = JSONObject()
            .put("seq", sequence)
            .put("at", Instant.now().toString())
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("type", type)
            .put("details", details)
        File(evidenceDir, "events.jsonl").appendText(event.toString() + "\n", Charsets.UTF_8)
        File(evidenceDir, "status.json").writeText(
            JSONObject()
                .put("updatedAt", Instant.now().toString())
                .put("lastEvent", type)
                .put("sessionId", sessionId)
                .put("events", sequence)
                .toString(2) + "\n",
            Charsets.UTF_8)
    }

    private fun updateStatus(value: String) {
        status.text = value
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        else -> "CODE_" + error
    }
}
