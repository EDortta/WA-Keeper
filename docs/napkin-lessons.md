# Napkin Lessons — WA-Keeper

Lições curtas e acionáveis, capturadas no fechamento de sessão.
Uma lição por entrada; sem narrativa.

## Formato
- `[YYYY-MM-DD] <work_id> - <lição>`
- `Action next time: <comportamento concreto a repetir/evitar>`

## Entradas
- `[2026-08-19] voice-commands-fase-0-3 - Nome estrangeiro como palavra de ativação ("Jeeves") transcreve de forma inconsistente no reconhecedor on-device pt-BR ("deeps", "diz", "açude"); uma palavra real do dicionário ("Godofredo") transcreve estável.`
- `Action next time: escolher palavra de ativação que exista no dicionário do idioma do reconhecedor, nunca um nome importado.`
- `[2026-08-19] voice-commands-fase-0-3 - RecognizerIntent.EXTRA_LANGUAGE precisa da string literal "pt-BR"; Locale.forLanguageTag("pt-BR").toString() produz "pt_BR" e cai em inglês silenciosamente.`
- `Action next time: passar tag BCP-47 literal para APIs de reconhecimento, e verificar o idioma efetivo no log em vez de assumir que o Locale converteu certo.`
- `[2026-09-01] epic3-image-review - Concílio de entrega, 6 lentes em 2 rodadas: 31 achados levantados, 13 corrigidos, 5 viraram teste unitário, 10 vivos ao fim da rodada 2, 4 perguntas parqueadas para o operador. Duas das 13 correções da rodada 1 abriram problema novo, e a rodada 2 pegou as duas — com as duas lentes chegando nelas de forma independente.`
- `Action next time: tratar toda correção de rodada 1 como código não revisado, e reservar a rodada 2 para verificá-las uma a uma antes de procurar achado novo — foi exatamente isso que pegou S1 e S2.`
- `[2026-09-01] epic3-image-review - Gatilho de captura de mídia por "o texto contém a palavra foto" (containsMatchIn) anexa imagem alheia a mensagem de texto por coincidência temporal. Corrigir com âncora (emoji no início, ou o texto ser só o rótulo) resolve; mas o strip de prefixo "Remetente: " acrescentado logo depois, para não perder grupo, reabriu o buraco em "Ana: fotos".`
- `Action next time: gatilho de captura de mídia nasce ancorado, e todo relaxamento posterior ("só para pegar o caso do grupo") precisa do seu próprio teste de falso positivo no mesmo commit — senão desfaz a âncora sem que nada acuse.`
- `[2026-09-01] epic3-image-review - Janela de tempo, carência de estabilização de arquivo e escada de retry foram escritas em três momentos, cada uma coerente sozinha e as três contraditórias juntas: a janela declarada de 12 s virou 9 s úteis, e a primeira varredura só conseguia achar arquivo ANTERIOR à notificação.`
- `Action next time: quando três constantes de tempo governam o mesmo fluxo, derivar umas das outras em código (ceiling = último retry − settle), nunca alinhá-las por comentário.`
- `[2026-09-01] epic3-image-review - Um worktree criado de um commit antigo não tem a infraestrutura de teste que já existe em development (app/src/test, testImplementation junit): o agente conclui "o repo não tem teste" e escreve sem rede.`
- `Action next time: ao abrir frente em worktree, conferir o merge-base e o que development ganhou depois dele ANTES de decidir o que existe no projeto.`

