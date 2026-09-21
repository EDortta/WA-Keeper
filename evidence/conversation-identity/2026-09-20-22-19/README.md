# Issue #56 — diagnóstico de identidade de conversas WhatsApp

Captura gerada em: 2026-09-20T22:20:01-03:00
Dispositivo: RX8Y903RVWD

## Arquivos

- `device.txt`: modelo, Android, versão dos pacotes WhatsApp/Business e WA Keeper.
- `notifications-raw.txt`: saída bruta de `adb shell dumpsys notification --noredact`.
- `notifications-summary.txt`: blocos candidatos de `com.whatsapp` e `com.whatsapp.w4b`, com linhas técnicas relevantes.

## Como interpretar

Compare os blocos de `notifications-summary.txt` das conversas que aparecem duplicadas no WA Keeper.
O campo que deve virar identidade estável é o que permanece igual para notificações da mesma conversa e muda entre conversas diferentes.

Prioridade de campos a validar contra a evidência real:

1. `shortcutId` / linhas contendo `shortcut`;
2. identificadores de `Person`, `people.list`, `locus` ou campos com `conversation`;
3. `groupKey` somente se diferenciar conversas reais sem colidir com resumos;
4. `tag` e `notification key` apenas se a evidência mostrar estabilidade entre reposts da mesma conversa;
5. título normalizado deve ser fallback, não fonte principal de verdade.
