package br.com.wanotifkeeper

/**
 * Palavras e frases-gatilho dos comandos de voz, em português do Brasil — dados puros,
 * sem lógica de parsing (isso fica em [VoiceCommandParser]). Extensível: novas frases
 * entram aqui como novas entradas de lista, sem tocar em fluxo de controle.
 */
object VoiceGrammar {

    /**
     * Palavra de ativação ("Godofredo, leia as últimas mensagens de..."). Sem ela a frase é
     * ignorada em silêncio — mesmo que bata com um padrão de comando — porque sem wake word
     * qualquer fala ambiente vira candidata, e isso deixa a intenção explícita.
     *
     * Era "Jeeves" — testado em aparelho real, o reconhecedor transcrevia diferente a cada
     * vez ("deeps", "diz", "açude", "gibs"...) por ser um nome estrangeiro fora do vocabulário
     * do modelo pt-BR. "Godofredo" é uma palavra real do português — o modelo deve transcrever
     * de forma muito mais consistente por já estar no vocabulário dele.
     */
    val WAKE_WORD_ALIASES = listOf("godofredo")

    /** "pelo whatsapp secundário" e variações — troca a conta só para aquele comando. */
    val ACCOUNT_OVERRIDE_PHRASES = listOf(
        "whatsapp secundario", "segundo whatsapp", "conta business", "whatsapp business", "pelo business"
    )

    /**
     * Verbos que iniciam o comando "leia as últimas mensagens de X". "Fala"/"falar" entraram
     * depois de um relato ao vivo de "nenhuma resposta": Esteban disse "Godofredo, fala para
     * mim as últimas cinco mensagens da Nanda" — frase natural pra um assistente de voz, mas
     * fora da lista, então o parser (corretamente, por design) ignorava em silêncio. O
     * reconhecedor tinha ouvido certinho; faltava o verbo no vocabulário.
     */
    val READ_LAST_VERBS = listOf("leia", "ler", "le", "mostra", "mostrar", "fala", "falar")

    /** Quantas mensagens ler quando o comando não pede um número específico. */
    const val DEFAULT_READ_COUNT = 5
    const val MAX_READ_COUNT = 10

    /**
     * Números falados. Usado na resposta ao menu de desambiguação ("dois", "segundo", ...) e
     * também como fallback pra contagem de "mostra as N últimas mensagens de X" quando a
     * pessoa fala o número por extenso em vez de dígito — "mostra as dez últimas..." batia
     * com o padrão do comando, mas a contagem só olhava `\d+`, então "dez" nunca virava 10 e
     * caía sem aviso pro padrão de 5 (bug real, achado ao investigar "sem resposta nenhuma"
     * pra esse comando). Vai até "dez" pra cobrir [MAX_READ_COUNT].
     */
    val NUMBER_WORDS = mapOf(
        "um" to 1, "uma" to 1, "primeiro" to 1, "primeira" to 1,
        "dois" to 2, "duas" to 2, "segundo" to 2, "segunda" to 2,
        "tres" to 3, "terceiro" to 3, "terceira" to 3,
        "quatro" to 4, "quarto" to 4, "quarta" to 4,
        "cinco" to 5, "quinto" to 5, "quinta" to 5,
        "seis" to 6, "sexto" to 6, "sexta" to 6,
        "sete" to 7, "setimo" to 7, "setima" to 7,
        "oito" to 8, "oitavo" to 8, "oitava" to 8,
        "nove" to 9, "nono" to 9, "nona" to 9,
        "dez" to 10, "decimo" to 10, "decima" to 10
    )
}
