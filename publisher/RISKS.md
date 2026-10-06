# Riscos de revisão Google Play

Estado inicial baseado na branch development em 2026-10-06.

| Item | Estado | Ação |
|---|---|---|
| targetSdk 34 | BLOQUEADOR | elevar para a API exigida pela Play e testar regressões |
| assinatura com debug keystore | BLOQUEADOR | criar configuração release própria para publicação |
| MANAGE_EXTERNAL_STORAGE | ALTO RISCO | remover da variante Play ou justificar categoria elegível |
| BIND_ACCESSIBILITY_SERVICE | ALTO RISCO | revisar uso de envio de mídia e disclosure; preferir alternativa sem Accessibility se possível |
| SCHEDULE_EXACT_ALARM | REVISAR | confirmar necessidade essencial e comportamento degradado |
| BIND_NOTIFICATION_LISTENER_SERVICE | REVISAR | manter apenas com disclosure claro e funcionalidade central |
| RECORD_AUDIO | REVISAR | confirmar uso, foreground service e processamento declarado |
| READ_CONTACTS | REVISAR | justificar busca/seleção de contatos e minimizar escopo |
| INTERNET | REVISAR | mapear exatamente quais fluxos usam rede |
| backup Google Drive | REVISAR | alinhar implementação, política de privacidade e Data Safety |
| ASR sherpa-onnx local | BOM | comprovar que áudio não é enviado quando usado em modo local |
| página docs anti-Play | BLOQUEADOR DE COMUNICAÇÃO | substituir antes da submissão pública |

## Regra

Nenhum risco é resolvido apenas por texto. Quando a política exigir mudança técnica, a variante Play deve mudar.
