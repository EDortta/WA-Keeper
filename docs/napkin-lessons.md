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

## 2026-09-01 · EPIC 4 (#18) · concílio de 2 rodadas, 6 lentes

42 achados levantados (24 na rodada 1, 18 na rodada 2), 20 fechados, 13 novos testes,
9 vivos levados ao operador. Teto de 2 rodadas respeitado.

**A lição que só a segunda rodada dá:** a correção do BLOCKER da rodada 1 desarmou, no
mesmo movimento, o item do *Definition of Done* que ela queria reforçar. Devolver a
tentativa no caso "notificação sem ação de resposta" zerou `attempts` — e a tela só
mostrava o erro quando `attempts > 0`. A impossibilidade que a issue manda registrar
sumiu da interface, com três KDocs afirmando o contrário. Nenhuma auto-revisão pega
isso: o autor da correção lê o que quis dizer, não o que escreveu.

**Segunda lição:** 11 dos 18 achados da rodada 2 foram *introduzidos pela correção da
rodada 1*. Rodar a segunda rodada contra o artefato **corrigido**, e obrigar cada achado
a se classificar como antigo / pré-existente / introduzido, é o que transforma a
contagem em informação em vez de ruído.
