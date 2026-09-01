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
