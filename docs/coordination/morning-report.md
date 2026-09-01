# Relatório da rodada — 2026-09-01

Status: **em execução.** Este arquivo é escrito durante a rodada, não no fim, para
sobreviver à queda de energia que o operador avisou ser possível.

## Primeira linha (§8)

Nada fechado ainda. Rodada armada às 09:05, janela até **23:25** (reboot às 23:30).

## Configuração desta rodada

Ver `unattended-run.md` nesta pasta. Resumo: 2 frentes em worktrees separados,
Opus 5 nas duas, 40 chamadas por frente, 2 rodadas de concílio no máximo,
1 build Gradle por vez, sem push, sem merge, sem deploy, sem aparelho de teste.

## Regime de manutenção descoberto

`nightly-reboot.timer` às 23:30 todo dia, confirmado por `last reboot` em 6 dias
seguidos. `/tmp` limpo às 23:51. Horizonte útil: 23:25.

## Por frente

### Frente A — EPIC 4 (#18) · `feature/epic-4-scheduled-messages`
- estado: **primeiro corte implementado e verde em build**, 5 commits (`a29b3d5..36a8d63`);
  concílio da entrega em andamento.
- passo zero: **resolvido por leitura** — o listener já extraía o `PendingIntent` de
  resposta e os `RemoteInput` do WhatsApp; faltava só disparar. Viável, sem permissão
  nova, sem automação de tela. Detalhe e limites no `RESUME.md` da 018.
- o que existe: tabela `scheduled_messages` (migration 4→5), claim atômico por
  `UPDATE ... WHERE state='PENDING'`, `ScheduledMessageCoordinator` sem Android,
  `ReplySender` atrás de interface, `ScheduledMessageTrigger` como único gancho no
  listener, e UI de armar/listar/editar/cancelar a partir da conversa.
- provado: `:app:testDebugUnitTest` verde, 35 testes (12 novos) — entrega única,
  claim perdido não envia, backoff, FAILED terminal, eco do próprio usuário não é
  gatilho. `:app:assembleDebug` verde.
- **não provado: nada rodou em aparelho.** `adb devices` vazio a rodada inteira.
- por que parou: —

### Frente B — EPIC 3 (#17/#19, PR #20) · `feature/epic-3-image-retention`
- estado: iniciando
- por que parou: —

## Esperando o operador

1. (nada ainda)
