# Relatório da rodada — 2026-09-01

Status: **em execução.** Este arquivo é escrito durante a rodada, não no fim, para
sobreviver à queda de energia que o operador avisou ser possível.

## Primeira linha (§8)

**Frente A fechou o primeiro corte da EPIC 4 (#18): 7 commits, 47 testes verdes, APK
debug construído — e nenhuma linha verificada em aparelho, porque não havia aparelho.**
Duas rodadas de concílio consumidas (teto), 42 achados levantados, 20 fechados, 9 vivos
levados ao operador em vez de uma terceira rodada.

## Configuração desta rodada

Ver `unattended-run.md` nesta pasta. Resumo: 2 frentes em worktrees separados,
Opus 5 nas duas, 40 chamadas por frente, 2 rodadas de concílio no máximo,
1 build Gradle por vez, sem push, sem merge, sem deploy, sem aparelho de teste.

## Regime de manutenção descoberto

`nightly-reboot.timer` às 23:30 todo dia, confirmado por `last reboot` em 6 dias
seguidos. `/tmp` limpo às 23:51. Horizonte útil: 23:25.

## Por frente

### Frente A — EPIC 4 (#18) · `feature/epic-4-scheduled-messages`
- estado: **primeiro corte entregue na branch**, 7 commits (`a29b3d5..HEAD`), duas
  rodadas de concílio consumidas. Sem PR, sem merge — a decisão é sua.
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
- provado depois do concílio: **47 testes, 0 falhas**; `assembleDebug` verde.
- **não provado: nada rodou em aparelho.** `adb devices` vazio a rodada inteira.
- **por que parou:** o teto de 2 rodadas de concílio foi atingido e 9 achados continuam
  vivos. O contrato manda parar e trazer a você em vez de abrir uma terceira rodada.
  Os 9 estão no item 7 de "Esperando o operador" do `RESUME.md` da 018.
- o que ficou esperando você: (a) confirmar no aparelho que a notificação do WhatsApp
  ainda expõe ação de resposta com `RemoteInput`, para as **duas** contas; (b) decidir
  se armar duas mensagens por conversa fere "mensagens encadeadas", que a #18 põe fora
  do primeiro corte; (c) corrigir a base da branch da frente B, que saiu de `main` e não
  de `development` — um `git diff` dela contra `development` **mente** e um
  `--ours/--theirs` apressado apaga os comandos de voz.

### Frente B — EPIC 3 (#17/#19, PR #20) · `feature/epic-3-image-retention`
- estado: iniciando
- por que parou: —

## Esperando o operador

1. **Frente A:** os 9 achados vivos depois da rodada 2 e as pendências de aparelho,
   todos escritos em `docs/issues/018-epic-4-mensagens-condicionadas-[open]/RESUME.md`,
   seção "Esperando o operador". Nenhum foi resolvido por suposição.
2. **Cross-frente:** `feature/epic-3-image-retention` tem `merge-base` em `main`, não em
   `development`. Corrigir a base antes de qualquer merge das duas frentes.
