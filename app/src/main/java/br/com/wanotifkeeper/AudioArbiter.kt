package br.com.wanotifkeeper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Autoridade única de saída de áudio do WA-Keeper.
 *
 * TTS e arquivos de áudio entram na mesma fila, portanto nunca se sobrepõem. Antes de
 * reproduzir, o arbiter pede AUDIOFOCUS_GAIN_TRANSIENT com willPauseWhenDucked=true: players
 * cooperativos (música, podcast etc.) pausam enquanto o WA-Keeper fala e retomam quando o foco
 * é devolvido.
 *
 * O mesmo objeto também é o semáforo entre microfone e alto-falante. Enquanto o microfone de
 * comandos está aberto, nenhuma nova saída começa; fala interrompida volta para o início da
 * fila e áudio em arquivo é pausado para continuar depois.
 */
class AudioArbiter private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private sealed class Output {
        data class Speech(val parts: List<SpeechPart>) : Output()
        data class FileAudio(val path: String) : Output()
    }

    private data class SpeechPart(val text: String, val volume: Float)

    private val queue = ArrayDeque<Output>()
    private var current: Output? = null
    private var currentSpeechPart = 0
    private var currentToken = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var playerPrepared = false

    private var microphoneActive = false
    private var focusHeld = false

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes)
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> onAudioFocusChange(change) }, main)
        .build()

    fun announce(sender: String, text: String) {
        val parts = mutableListOf<SpeechPart>()
        val prefix = "$sender diz:"
        var first = true
        for (segment in UrlHints.segments(text)) {
            when (segment) {
                is UrlHints.Segment.Text -> {
                    val phrase = if (first) "$prefix ${segment.text}" else segment.text
                    if (phrase.isNotBlank()) parts += SpeechPart(phrase, NORMAL_VOLUME)
                }
                is UrlHints.Segment.Link -> parts += SpeechPart("(${segment.label})", LINK_VOLUME)
            }
            first = false
        }
        if (parts.isEmpty()) parts += SpeechPart(prefix, NORMAL_VOLUME)
        enqueue(Output.Speech(parts))
    }

    /** TTS manual do conteúdo original, sem prefixar remetente. */
    fun speakText(text: String) {
        if (text.isBlank()) return
        enqueue(Output.Speech(listOf(SpeechPart(text, NORMAL_VOLUME))))
    }

    /** Aviso/pergunta do motor de voz, sem prefixo de remetente. */
    fun say(text: String) = speakText(text)

    fun play(path: String) {
        if (path.isBlank()) return
        enqueue(Output.FileAudio(path))
    }

    /** Inclui o item em execução e tudo o que ainda está esperando na fila. */
    fun isBusy(): Boolean = synchronized(lock) { current != null || queue.isNotEmpty() }

    /**
     * Semáforo de privacidade/concorrência: microfone aberto implica zero saída do WA-Keeper.
     * Ao fechar, aguardamos dois segundos antes de retomar para não capturar o próprio áudio.
     */
    fun setMicrophoneActive(active: Boolean) {
        synchronized(lock) {
            if (microphoneActive == active) return
            microphoneActive = active
            if (active) {
                when (val now = current) {
                    is Output.Speech -> {
                        currentToken++
                        runCatching { tts?.stop() }
                        queue.addFirst(now)
                        current = null
                        currentSpeechPart = 0
                    }
                    is Output.FileAudio -> runCatching { player?.pause() }
                    null -> Unit
                }
                abandonFocusLocked()
            } else {
                main.postDelayed({
                    synchronized(lock) {
                        if (!microphoneActive) resumeLocked()
                    }
                }, RESUME_AFTER_MIC_MS)
            }
        }
    }

    private fun enqueue(output: Output) {
        synchronized(lock) {
            queue.addLast(output)
            resumeLocked()
        }
    }

    /** Retoma o item pausado ou inicia o próximo item da fila. Chamado sempre sob [lock]. */
    private fun resumeLocked() {
        if (microphoneActive) return

        when (val now = current) {
            is Output.FileAudio -> {
                if (!requestFocusLocked()) return
                if (playerPrepared) runCatching { player?.start() }
                return
            }
            is Output.Speech -> {
                if (!requestFocusLocked()) return
                startSpeechPartLocked(now)
                return
            }
            null -> Unit
        }

        val next = queue.removeFirstOrNull()
        if (next == null) {
            abandonFocusLocked()
            return
        }
        if (!requestFocusLocked()) {
            queue.addFirst(next)
            main.postDelayed({ synchronized(lock) { resumeLocked() } }, FOCUS_RETRY_MS)
            return
        }

        current = next
        currentSpeechPart = 0
        currentToken++
        when (next) {
            is Output.Speech -> startSpeechPartLocked(next)
            is Output.FileAudio -> startFileLocked(next)
        }
    }

    private fun ensureTtsLocked() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            synchronized(lock) {
                if (status != TextToSpeech.SUCCESS) {
                    ttsReady = false
                    failCurrentSpeechLocked()
                    return@synchronized
                }
                tts?.language = Locale.forLanguageTag("pt-BR")
                tts?.setAudioAttributes(audioAttributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = onTtsPartFinished(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onTtsPartFinished(utteranceId)
                })
                ttsReady = true
                resumeLocked()
            }
        }
    }

    private fun startSpeechPartLocked(speech: Output.Speech) {
        ensureTtsLocked()
        if (!ttsReady || microphoneActive) return
        if (current !== speech) return

        if (currentSpeechPart >= speech.parts.size) {
            finishCurrentLocked()
            return
        }

        val part = speech.parts[currentSpeechPart]
        val id = "$currentToken:$currentSpeechPart"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, part.volume)
        }
        val result = tts?.speak(part.text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (result == TextToSpeech.ERROR) {
            currentSpeechPart++
            startSpeechPartLocked(speech)
        }
    }

    private fun onTtsPartFinished(utteranceId: String?) {
        synchronized(lock) {
            val speech = current as? Output.Speech ?: return
            val expected = "$currentToken:$currentSpeechPart"
            if (utteranceId != expected) return
            currentSpeechPart++
            if (currentSpeechPart < speech.parts.size) startSpeechPartLocked(speech)
            else finishCurrentLocked()
        }
    }

    private fun failCurrentSpeechLocked() {
        if (current is Output.Speech) finishCurrentLocked()
    }

    private fun startFileLocked(file: Output.FileAudio) {
        releasePlayerLocked()
        playerPrepared = false
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(audioAttributes)
        mp.setOnCompletionListener {
            synchronized(lock) {
                if (player === it) finishCurrentLocked()
            }
        }
        mp.setOnErrorListener { mediaPlayer, _, _ ->
            synchronized(lock) {
                if (player === mediaPlayer) finishCurrentLocked()
            }
            true
        }
        mp.setOnPreparedListener {
            synchronized(lock) {
                if (player !== it || current !== file) return@synchronized
                playerPrepared = true
                if (!microphoneActive && focusHeld) runCatching { it.start() }
            }
        }
        runCatching {
            mp.setDataSource(file.path)
            mp.prepareAsync()
        }.onFailure {
            finishCurrentLocked()
        }
    }

    private fun finishCurrentLocked() {
        releasePlayerLocked()
        current = null
        currentSpeechPart = 0
        currentToken++
        resumeLocked()
    }

    private fun releasePlayerLocked() {
        runCatching { player?.release() }
        player = null
        playerPrepared = false
    }

    private fun requestFocusLocked(): Boolean {
        if (focusHeld) return true
        focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonFocusLocked() {
        if (!focusHeld) return
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        focusHeld = false
    }

    private fun onAudioFocusChange(change: Int) {
        synchronized(lock) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    focusHeld = true
                    if (!microphoneActive) resumeLocked()
                }
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    focusHeld = false
                    when (val now = current) {
                        is Output.Speech -> {
                            currentToken++
                            runCatching { tts?.stop() }
                            queue.addFirst(now)
                            current = null
                            currentSpeechPart = 0
                        }
                        is Output.FileAudio -> runCatching { player?.pause() }
                        null -> Unit
                    }
                }
            }
        }
    }

    companion object {
        private const val NORMAL_VOLUME = 1f
        private const val LINK_VOLUME = 0.5f
        private const val RESUME_AFTER_MIC_MS = 2_000L
        private const val FOCUS_RETRY_MS = 1_000L

        @Volatile private var instance: AudioArbiter? = null

        fun get(context: Context): AudioArbiter =
            instance ?: synchronized(this) {
                instance ?: AudioArbiter(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Modo manual e deliberado para ouvir mensagens novas mesmo parado (ex.: sozinho no escritório).
 * Não é persistido: se o processo morrer, volta desligado — comportamento seguro em ambiente novo.
 */
object ManualReadMode {
    @Volatile private var value = false

    fun isEnabled(): Boolean = value

    @Synchronized
    fun toggle(): Boolean {
        value = !value
        return value
    }

    @Synchronized
    fun disable() {
        value = false
    }
}
