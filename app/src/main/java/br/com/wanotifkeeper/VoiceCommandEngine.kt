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
 * for reconhecido via [VoiceCommandParser]. Exige a palavra de ativação ("Godofredo") antes
 * de qualquer comando; o resto da fala ambiente é ignorado em silêncio.
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
    private val onRecognitionWorking: () -> Unit,
    /** Avisa que a sessão pedida pelo botão terminou — por fala entendida ou por silêncio. */
    private val onDirectSessionEnded: () -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var active = false
    private var errorStreak = 0

    // Cada ciclo de escuta reinicia o SpeechRecognizer, e o serviço on-device (não o app) toca
    // um aviso sonoro próprio a cada início/fim de sessão — não tem API pública pra silenciar
    // isso sem também silenciar notificação de verdade (mesmo stream de áudio). Esteban relatou
    // ao vivo o som repetindo indefinidamente ("plac..plic-pluc") como desagradável enquanto
    // ninguém fala nada. O que dá pra controlar é a CADÊNCIA: fica responsivo (reinicia na hora)
    // nos primeiros ciclos — é quando a pessoa provavelmente está prestes a falar — e vai
    // espaçando os reinícios se ninguém disser nada de reconhecível, em vez de manter o mesmo
    // ritmo pra sempre. Zera assim que algo é ouvido (onResults) ou quando a escuta liga de novo.
    private var noSpeechStreak = 0

    // Cada geração é uma "sessão" do recognizer. Callbacks de uma sessão velha (ex.: um
    // destroy() que ainda não terminou de verdade quando uma nova sessão já começou) são
    // ignorados — sem isso, duas sessões concorrentes cada uma reagenda sua própria re-escuta,
    // e o motor entra num loop de reinício que nunca se estabiliza (visto em teste real).
    private val generation = AtomicInteger(0)

    // Falar "Godofredo" e pausar antes do pedido é comportamento humano normal — cada pausa
    // vira um turno de reconhecimento separado, então nenhum dos dois teria a palavra de
    // ativação junto com o comando. Se um turno disser só a palavra, o turno seguinte (dentro
    // da janela) é tentado como comando mesmo sem repeti-la.
    @Volatile private var wakeWordArmedUntil = 0L

    /**
     * Sessão pedida pelo botão de microfone. É um modo, não um detalhe: no ciclo normal o motor
     * REINICIA a escuta indefinidamente à espera da palavra de ativação, e cada reinício faz o
     * serviço on-device tocar seu próprio aviso sonoro — o "plac..plic-pluc" que o operador
     * descreveu como insuportável, e que nenhuma API pública silencia.
     *
     * Quando o toque é que abriu o microfone, não há o que ficar vigiando: a pessoa vai falar
     * agora. Então é UMA sessão só — um aviso no início, um no fim — e acabou. Terminou sem
     * fala? Não reinicia; o botão volta ao normal e quem decide tentar de novo é a pessoa.
     */
    @Volatile private var directMode = false

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
        noSpeechStreak = 0
        wakeWordArmedUntil = 0L
        main.post { createAndListen() }
    }

    /**
     * Botão de microfone: a pessoa PEDIU para falar um comando, então a palavra de ativação
     * não faz sentido — ela já disse "é comigo" tocando o botão. Reusa a mesma janela de graça
     * que existe para quem fala "Godofredo" e pausa antes do pedido ([wakeWordArmedUntil]),
     * só que aberta por toque em vez de por fala.
     *
     * A janela é mais larga que a da fala porque aqui há um caminho humano no meio: tocar o
     * botão, levar o telefone à boca e só então falar.
     */
    /**
     * @param restart recomeçar a sessão de reconhecimento agora. Só faz sentido quando o motor
     *   **já estava** ouvindo e pode estar num backoff longo (até 30 s sem ninguém falar):
     *   esperar o próximo ciclo faria o botão parecer quebrado. Quando o motor acabou de ser
     *   ligado pelo mesmo toque, recomeçar é destrutivo — a sessão recém-criada é destruída e
     *   recriada em milissegundos, o reconhecedor devolve RECOGNIZER_BUSY, o serviço on-device
     *   toca o beep de início duas vezes e a escuta só fica pronta ~1,1 s depois. Visto no
     *   aparelho: `startListening ok (gen=11)` / `(gen=12)` / `RECOGNIZER_BUSY`.
     */
    fun armDirectCommand(restart: Boolean) {
        wakeWordArmedUntil = System.currentTimeMillis() + DIRECT_COMMAND_GRACE_MS
        directMode = true
        noSpeechStreak = 0
        if (!active) {
            start()
            return
        }
        if (restart) main.post { recreate(generation.get()) }
    }

    fun stop() {
        if (!active) return
        active = false
        directMode = false
        generation.incrementAndGet()
        disambiguation = null
        wakeWordArmedUntil = 0L
        main.post {
            // cancel() antes do destroy() é o que de fato aborta uma sessão de reconhecimento
            // já em andamento junto do serviço on-device; destroy() sozinho só desconecta o
            // client e devolve os recursos — vezes sem conta o serviço on-device (visto em
            // Samsung) segue com o ciclo de escuta/beep já disparado até completar por conta
            // própria, e só um force-stop do app derrubava de vez (bug real, não hipótese).
            recognizer?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
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
            if (directMode) {
                // Uma sessão só precisa esperar a pessoa levar o telefone à boca. Sem isto ela
                // fecha por silêncio em ~2 s e, no modo direto, não há reinício para compensar.
                // São dicas: o reconhecedor on-device pode ignorá-las, e o modo continua correto
                // se ignorar — só menos paciente.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, DIRECT_MIN_SESSION_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, DIRECT_SILENCE_MS)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    DIRECT_SILENCE_MS
                )
            }
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

    /**
     * Fecha a sessão do botão. Não desliga o motor por conta própria: quem decide se o
     * microfone continua aberto é o gate (movimento, timer manual), e é o serviço que sabe
     * disso — por isso o aviso sai por [onDirectSessionEnded].
     */
    private fun endDirectSession() {
        directMode = false
        wakeWordArmedUntil = 0L
        main.post { onDirectSessionEnded() }
    }

    /** Volta ao ciclo normal depois da sessão do botão, quando o gate segue aberto. */
    fun resumeAfterDirect() {
        if (!active || directMode) return
        main.post { recreate(generation.get()) }
    }

    private fun relistenSoon(gen: Int, delayMs: Long = COOLDOWN_MS) {
        if (!active || gen != generation.get()) return
        main.postDelayed({ listenNext(gen) }, delayMs)
    }

    /** Recria do zero: mais seguro que reusar um recognizer que acabou de dar BUSY/erro. */
    private fun recreate(gen: Int) {
        if (!active || gen != generation.get()) return
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
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
            noSpeechStreak = 0
            onRecognitionWorking()
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            android.util.Log.d(TAG, "ouviu: \"$text\"")
            if (!text.isNullOrBlank()) handleTranscript(text)
            if (directMode) endDirectSession() else relistenSoon(gen)
        }

        override fun onError(error: Int) {
            if (!current()) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Nada reconhecível nesse ciclo — espaça o próximo reinício (e o aviso
                    // sonoro que vem junto) em vez de manter o mesmo ritmo pra sempre. Volta a
                    // ficar responsivo assim que algo for de fato ouvido (onResults zera).
                    if (directMode) {
                        // Modo direto: silêncio encerra, não reinicia. É esta linha que impede o
                        // "plac..plic-pluc" de voltar quando o microfone foi aberto por toque.
                        android.util.Log.d(TAG, "${errorName(error)} — sessão do botão encerrada sem fala")
                        errorStreak = 0
                        endDirectSession()
                        return
                    }
                    val delay = NO_SPEECH_BACKOFF_MS.getOrElse(noSpeechStreak) { NO_SPEECH_BACKOFF_MS.last() }
                    android.util.Log.d(TAG, "${errorName(error)} — reescutando em ${delay}ms (streak=$noSpeechStreak)")
                    noSpeechStreak++
                    errorStreak = 0
                    relistenSoon(gen, delay)
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

    /** Resultado de tentar entender um turno — três jeitos de "não virou um comando" que pedem
     * reações bem diferentes do motor (ver [resolveCommand]). */
    private sealed class Resolution {
        data class Parsed(val command: ParsedVoiceCommand) : Resolution()
        /** Só a palavra de ativação, nada mais — janela de graça armada, fica quieto. */
        object WaitingForCommand : Resolution()
        /** A frase era claramente dirigida ao Godofredo (tinha a palavra, ou caiu dentro da
         * janela de graça) mas não bateu com nenhum comando conhecido — avisa, não fica mudo. */
        object Unrecognized : Resolution()
        /** Sem a palavra de ativação e fora da janela de graça — fala alheia, ignora quieto. */
        object NotForUs : Resolution()
    }

    /**
     * Resolve um comando a partir da transcrição, cobrindo o caso de "Godofredo" dito sozinho
     * num turno e o pedido só chegar no próximo. Nunca reusa a janela de graça duas vezes:
     * uma vez consumida (com sucesso ou não), fecha.
     *
     * Antes só devolvia null pra qualquer "não virou comando" — e o motor ficava mudo tanto pra
     * "ainda esperando o resto" quanto pra "a pessoa pediu algo real e não bati". Esteban
     * relatou ao vivo exatamente essa segunda situação como "eu falo e não faz nada": o
     * reconhecedor ouvia certinho, o parser não reconhecia o verbo/padrão, e o motor ficava
     * calado — silêncio ali é o bug, não a política de "nunca adivinhar" em si (que continua
     * valendo pra fala sem a palavra de ativação, caso [Resolution.NotForUs]).
     */
    private fun resolveCommand(raw: String): Resolution {
        VoiceCommandParser.parse(raw)?.let {
            wakeWordArmedUntil = 0L
            return Resolution.Parsed(it)
        }

        if (wakeWordArmedUntil > System.currentTimeMillis()) {
            wakeWordArmedUntil = 0L
            VoiceCommandParser.parseCommandOnly(raw)?.let {
                android.util.Log.d(TAG, "comando aceito sem repetir a palavra de ativação (janela de graça)")
                return Resolution.Parsed(it)
            }
            // Dentro da janela de graça, mas o texto não virou nenhum comando conhecido — a
            // pessoa estava claramente respondendo ao Godofredo, só não do jeito que ele entende.
            return Resolution.Unrecognized
        }

        val remainder = VoiceCommandParser.remainderAfterWakeWord(raw)
        if (remainder != null) {
            if (remainder.isEmpty()) {
                wakeWordArmedUntil = System.currentTimeMillis() + WAKE_WORD_GRACE_MS
                android.util.Log.d(TAG, "palavra de ativação ouvida sozinha — aguardando o comando")
                return Resolution.WaitingForCommand
            }
            // Palavra de ativação + algo mais na mesma frase, mas nada bateu.
            return Resolution.Unrecognized
        }

        return Resolution.NotForUs
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

        val parsed = when (val resolution = resolveCommand(raw)) {
            is Resolution.Parsed -> resolution.command
            Resolution.WaitingForCommand -> return
            Resolution.NotForUs -> {
                android.util.Log.d(TAG, "não é comando, ignorando: \"$raw\"")
                return
            }
            Resolution.Unrecognized -> {
                android.util.Log.d(TAG, "não entendi o comando, avisando: \"$raw\"")
                say(NOT_UNDERSTOOD_MESSAGE)
                return
            }
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
        /** Quanto tempo depois de ouvir só a palavra de ativação o próximo turno ainda conta. */
        private const val WAKE_WORD_GRACE_MS = 8000L

        /** Janela do comando pedido por botão: tocar, levar à boca e falar leva mais tempo. */
        private const val DIRECT_COMMAND_GRACE_MS = 20_000L

        /** Duração mínima pedida à sessão do botão — tempo de levar o telefone à boca. */
        private const val DIRECT_MIN_SESSION_MS = 8_000

        /** Silêncio que encerra a fala no modo direto. */
        private const val DIRECT_SILENCE_MS = 2_000
        /** Falado quando a frase era claramente pro Godofredo mas não bateu com nenhum comando —
         * silêncio nesse caso é o que Esteban relatou como "eu falo e não faz nada" ao vivo. */
        private const val NOT_UNDERSTOOD_MESSAGE =
            "Não entendi. Tente algo como: Godofredo, leia as últimas mensagens de Fulano."
        /**
         * Atraso antes de reiniciar a escuta depois de um ciclo sem nada reconhecível, indexado
         * por [noSpeechStreak] (o último valor se repete além do fim da lista). Só o primeiro
         * ciclo (índice 0) precisa ficar em 0ms — é o único que importa pra pegar o pedido logo
         * depois de "acabou de abrir a janela" ou "acabou de ouvir só a palavra de ativação"
         * (janela de graça de 8s); qualquer ciclo depois desse já não influencia mais aquela
         * janela. Esteban relatou que o backoff anterior (3/6/10/15/20s, só começando a espaçar
         * no 4º ciclo ocioso) ainda incomodava — este começa a espaçar já no 2º ciclo e chega
         * ao teto mais rápido.
         */
        private val NO_SPEECH_BACKOFF_MS = listOf(0L, 2000L, 5000L, 10000L, 20000L, 30000L)
    }
}
