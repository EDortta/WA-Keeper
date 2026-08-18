package br.com.wanotifkeeper

import android.content.Context
import android.media.AudioAttributes
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
    private val pending = ArrayDeque<String>()

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
                    while (pending.isNotEmpty()) enqueue(pending.removeFirst())
                }
            }
        }
    }

    /** Lê "Fulano diz: texto" em voz alta. */
    fun announce(sender: String, text: String) {
        val phrase = "$sender diz: $text"
        if (ready) enqueue(phrase)
        else synchronized(pending) { pending.addLast(phrase) }
    }

    private fun enqueue(phrase: String) {
        // QUEUE_ADD: mensagens em rajada são lidas em sequência, sem se cortarem.
        tts?.speak(phrase, TextToSpeech.QUEUE_ADD, null, phrase.hashCode().toString())
    }

    fun shutdown() {
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
