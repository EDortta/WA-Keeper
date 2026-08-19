package br.com.wanotifkeeper

/**
 * Palavras e frases-gatilho dos comandos de voz, em português do Brasil — dados puros,
 * sem lógica de parsing (isso fica em [VoiceCommandParser]). Extensível: novas frases
 * entram aqui como novas entradas de lista, sem tocar em fluxo de controle.
 */
object VoiceGrammar {

    /**
     * Palavra de ativação ("Jeeves, leia as últimas mensagens de..."). Sem ela a frase é
     * ignorada em silêncio — mesmo que bata com um padrão de comando — porque sem wake word
     * nenhuma qualquer fala ambiente vira candidata, e isso deixa a intenção explícita.
     * Várias grafias porque o reconhecedor transcreve nome estrangeiro de formas diferentes.
     */
    val WAKE_WORD_ALIASES = listOf("jeeves", "jives", "jivis", "gibs")

    /** "pelo whatsapp secundário" e variações — troca a conta só para aquele comando. */
    val ACCOUNT_OVERRIDE_PHRASES = listOf(
        "whatsapp secundario", "segundo whatsapp", "conta business", "whatsapp business", "pelo business"
    )

    /** Verbos que iniciam o comando "leia as últimas mensagens de X". */
    val READ_LAST_VERBS = listOf("leia", "ler", "le", "mostra", "mostrar")

    /** Quantas mensagens ler quando o comando não pede um número específico. */
    const val DEFAULT_READ_COUNT = 5
    const val MAX_READ_COUNT = 10

    /** Números falados usados na resposta ao menu de desambiguação ("dois", "segundo", ...). */
    val NUMBER_WORDS = mapOf(
        "um" to 1, "uma" to 1, "primeiro" to 1, "primeira" to 1,
        "dois" to 2, "duas" to 2, "segundo" to 2, "segunda" to 2,
        "tres" to 3, "terceiro" to 3, "terceira" to 3,
        "quatro" to 4, "quarto" to 4, "quarta" to 4,
        "cinco" to 5, "quinto" to 5, "quinta" to 5,
        "seis" to 6, "sexto" to 6, "sexta" to 6
    )
}
