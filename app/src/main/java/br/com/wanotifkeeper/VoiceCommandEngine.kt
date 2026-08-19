package br.com.wanotifkeeper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Motor de comandos de voz: liga o [SpeechRecognizer] on-device enquanto a janela de
 * ativação estiver aberta (ver NotifListenerService.runVoiceGateLoop) e interpreta o que
 * for reconhecido via [VoiceCommandParser]. Sem wake word — qualquer frase é candidata,
 * mas só age quando bate com um padrão conhecido; o resto é ignorado em silêncio.
 *
 * Reconhecimento é sempre on-device (nunca cai pra nuvem): quem chama [start] já garante
 * SDK ≥ 31, então não há branch de fallback pra reconhecedor de rede aqui.
 *
 * O SpeechRecognizer só pode ser criado/chamado na main thread; o resto do serviço roda
 * em Dispatchers.IO, então toda chamada a ele passa pelo [main] handler.
 */
class VoiceCommandEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: NotifDao,
    private val say: (String) -> Unit,
    private val announce: (String, String) -> Unit,
    private val isSpeakerBusy: () -> Boolean,
    private val defaultAccountPkg: () -> String,
    private val onSpeechPackMissing: () -> Unit,
    private val onRecognitionWorking: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var active = false
    private var errorStreak = 0

    // Cada geração é uma "sessão" do recognizer. Callbacks de uma sessão velha (ex.: um
    // destroy() que ainda não terminou de verdade quando uma nova sessão já começou) são
    // ignorados — sem isso, duas sessões concorrentes cada uma reagenda sua própria re-escuta,
    // e o motor entra num loop de reinício que nunca se estabiliza (visto em teste real).
    private val generation = AtomicInteger(0)

    @Volatile private var disambiguation: Disambiguation? = null

    private class Disambiguation(
        val candidates: List<String>,
        val pkg: String,
        val count: Int,
        var attempts: Int = 0
    )

    fun start() {
        if (active) return
        active = true
        errorStreak = 0
        main.post { createAndListen() }
    }

    fun stop() {
        if (!active) return
        active = false
        generation.incrementAndGet()
        disambiguation = null
        main.post {
            recognizer?.let { runCatching { it.destroy() } }
            recognizer = null
        }
    }

    /**
     * Sem checagem prévia de disponibilidade: `isOnDeviceRecognitionAvailable` já se mostrou
     * conservadora demais em pelo menos um aparelho real com o pacote de voz confirmadamente
     * baixado. Tenta direto e deixa o [onError] (ERROR_LANGUAGE_*) ser a única fonte de verdade
     * sobre "indisponível" — é o que o probe-and-catch original já previa.
     */
    private fun createAndListen() {
        if (!active) return
        val myGen = generation.incrementAndGet()
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        if (r == null) {
            android.util.Log.d(TAG, "createOnDeviceSpeechRecognizer retornou null")
            active = false
            onSpeechPackMissing()
            return
        }
        recognizer = r
        r.setRecognitionListener(SessionListener(myGen))
        listenNext(myGen)
    }

    private fun listenNext(gen: Int) {
        if (!active || gen != generation.get()) return
        if (isSpeakerBusy()) {
            main.postDelayed({ listenNext(gen) }, BUSY_POLL_MS)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Literal, não via Locale(...).toString() — isso devolve "pt_BR" (underscore, formato
            // legado do Java) e o reconhecedor ignora silenciosamente, caindo pro inglês do sistema.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        }
        runCatching { recognizer?.startListening(intent) }
            .onSuccess { android.util.Log.d(TAG, "startListening ok (gen=$gen)") }
            .onFailure { android.util.Log.d(TAG, "startListening falhou: $it") }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "código $error"
    }

    private fun relistenSoon(gen: Int, delayMs: Long = COOLDOWN_MS) {
        if (!active || gen != generation.get()) return
        main.postDelayed({ listenNext(gen) }, delayMs)
    }

    /** Recria do zero: mais seguro que reusar um recognizer que acabou de dar BUSY/erro. */
    private fun recreate(gen: Int) {
        if (!active || gen != generation.get()) return
        recognizer?.let { runCatching { it.destroy() } }
        createAndListen()
    }

    private inner class SessionListener(private val gen: Int) : RecognitionListener {
        private fun current() = active && gen == generation.get()

        override fun onReadyForSpeech(params: Bundle?) {
            if (current()) android.util.Log.d(TAG, "pronto pra ouvir")
        }
        override fun onBeginningOfSpeech() {
            if (current()) android.util.Log.d(TAG, "detectou fala começando")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onResults(results: Bundle) {
            if (!current()) return
            errorStreak = 0
            onRecognitionWorking()
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            android.util.Log.d(TAG, "ouviu: \"$text\"")
            if (!text.isNullOrBlank()) handleTranscript(text)
            relistenSoon(gen)
        }

        override fun onError(error: Int) {
            if (!current()) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    android.util.Log.d(TAG, "${errorName(error)} — reescutando")
                    errorStreak = 0
                    relistenSoon(gen, 0L)
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    android.util.Log.d(TAG, "pacote de voz indisponível (${errorName(error)})")
                    active = false
                    onSpeechPackMissing()
                }
                else -> {
                    // BUSY entra aqui também: recriar na hora, sem esperar, é o que causava o
                    // loop de reinício visto em teste real — o serviço on-device não tinha tempo
                    // de se recompor. Backoff crescente por tentativa dá esse tempo.
                    errorStreak++
                    android.util.Log.d(TAG, "erro do reconhecedor: ${errorName(error)} (streak=$errorStreak)")
                    if (errorStreak >= MAX_ERROR_STREAK) {
                        active = false
                        android.util.Log.d(TAG, "erros demais seguidos — parando o motor nesta sessão")
                    } else {
                        main.postDelayed({ recreate(gen) }, RETRY_MS * errorStreak)
                    }
                }
            }
        }
    }

    private fun handleTranscript(raw: String) {
        val pending = disambiguation
        if (pending != null) {
            val choice = VoiceCommandParser.parseDisambiguationAnswer(raw, pending.candidates.size)
            if (choice == null) {
                pending.attempts++
                android.util.Log.d(TAG, "desambiguação: resposta \"$raw\" não bateu (tentativa ${pending.attempts})")
                if (pending.attempts >= MAX_DISAMBIGUATION_ATTEMPTS) {
                    disambiguation = null
                    say("Não consegui identificar o destinatário. Tente de novo mais tarde.")
                } else {
                    say(disambiguationPrompt(pending.candidates))
                }
                return
            }
            android.util.Log.d(TAG, "desambiguação: escolheu ${pending.candidates[choice - 1]}")
            disambiguation = null
            readLast(pending.candidates[choice - 1], pending.pkg, pending.count)
            return
        }

        val parsed = VoiceCommandParser.parse(raw)
        if (parsed == null) {
            android.util.Log.d(TAG, "não é comando, ignorando: \"$raw\"")
            return
        }
        val pkg = parsed.accountOverride ?: defaultAccountPkg()
        android.util.Log.d(TAG, "comando reconhecido: alvo=\"${parsed.command.target}\" count=${parsed.command.count} pkg=$pkg")
        scope.launch {
            val senders = try { dao.sendersByPackage(pkg) } catch (e: Exception) { emptyList() }
            android.util.Log.d(TAG, "remetentes conhecidos em $pkg: $senders")
            when (val result = VoiceSenderMatcher.match(parsed.command.target, senders)) {
                is VoiceSenderMatcher.MatchResult.Confident -> {
                    android.util.Log.d(TAG, "casou com \"${result.sender}\"")
                    readLast(result.sender, pkg, parsed.command.count)
                }
                is VoiceSenderMatcher.MatchResult.Ambiguous -> {
                    android.util.Log.d(TAG, "ambíguo: ${result.candidates}")
                    disambiguation = Disambiguation(result.candidates, pkg, parsed.command.count)
                    say(disambiguationPrompt(result.candidates))
                }
                VoiceSenderMatcher.MatchResult.NoMatch -> {
                    android.util.Log.d(TAG, "nenhum remetente bateu com \"${parsed.command.target}\"")
                    say("Não encontrei nenhuma conversa com esse nome.")
                }
            }
        }
    }

    private fun readLast(sender: String, pkg: String, count: Int) {
        scope.launch {
            val messages = try { dao.lastNForSender(sender, pkg, count) } catch (e: Exception) { emptyList() }
            android.util.Log.d(TAG, "lendo ${messages.size} mensagem(ns) de $sender")
            if (messages.isEmpty()) {
                say("Não achei mensagens de $sender.")
            } else {
                say("Últimas mensagens de $sender:")
                messages.asReversed().forEach { announce(sender, it.text) }
            }
        }
    }

    private fun disambiguationPrompt(candidates: List<String>): String {
        val options = candidates.mapIndexed { i, name -> "${i + 1} - $name" }.joinToString(", ")
        return "Não entendi o destinatário. Seria um destes? $options"
    }

    companion object {
        private const val TAG = "WAK-VoiceCmd"
        private const val BUSY_POLL_MS = 400L
        private const val COOLDOWN_MS = 700L
        private const val RETRY_MS = 600L
        private const val MAX_ERROR_STREAK = 5
        private const val MAX_DISAMBIGUATION_ATTEMPTS = 2
    }
}
