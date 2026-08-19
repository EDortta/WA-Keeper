package br.com.wanotifkeeper

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Leitura em voz alta via TTS nativo do Android.
 *
 * Inicialização é assíncrona; mensagens que chegam antes do motor ficar pronto são
 * enfileiradas e faladas assim que possível. Usa o canal de assistente para se
 * comportar bem com o áudio do carro (Bluetooth, ducking de música).
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayDeque<Utterance>()

    private data class Utterance(val phrase: String, val volume: Float)

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("pt-BR")
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ready = true
                synchronized(pending) {
                    while (pending.isNotEmpty()) {
                        val u = pending.removeFirst()
                        enqueue(u.phrase, u.volume)
                    }
                }
            }
        }
    }

    /**
     * Lê "Fulano diz: texto" em voz alta. URLs no texto viram um aside resumido
     * ("link do YouTube") em volume mais baixo, sem baixar nada (ver [UrlHints]) —
     * cada trecho é uma chamada separada de fala em QUEUE_ADD, então soa como uma
     * frase só apesar da mudança de volume no meio.
     */
    fun announce(sender: String, text: String) {
        val prefix = "$sender diz:"
        var first = true
        for (segment in UrlHints.segments(text)) {
            when (segment) {
                is UrlHints.Segment.Text -> {
                    val phrase = if (first) "$prefix ${segment.text}" else segment.text
                    speak(phrase, NORMAL_VOLUME)
                }
                is UrlHints.Segment.Link -> speak("(${segment.label})", LINK_VOLUME)
            }
            first = false
        }
    }

    private fun speak(phrase: String, volume: Float) {
        if (ready) enqueue(phrase, volume)
        else synchronized(pending) { pending.addLast(Utterance(phrase, volume)) }
    }

    private fun enqueue(phrase: String, volume: Float) {
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        // QUEUE_ADD: mensagens em rajada são lidas em sequência, sem se cortarem.
        tts?.speak(phrase, TextToSpeech.QUEUE_ADD, params, phrase.hashCode().toString())
    }

    fun shutdown() {
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val NORMAL_VOLUME = 1f
        /** URLs viram um aside falado mais baixo, como se estivesse entre parênteses. */
        private const val LINK_VOLUME = 0.5f
    }
}
