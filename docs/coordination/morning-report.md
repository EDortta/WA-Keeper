# Relatório da rodada — 2026-09-01

Status: **em execução.** Este arquivo é escrito durante a rodada, não no fim, para
sobreviver à queda de energia que o operador avisou ser possível.

## Primeira linha (§8)

**Frente B fechou a revisão e o concílio (2 rodadas, teto), mas a branch NÃO está pronta
para merge**: 10 achados seguem vivos, 2 deles introduzidos pelas próprias correções da
rodada 1, e o contrato proíbe uma terceira rodada. Detalhe e recomendação no
`docs/issues/017-epic-3-retencao-imagens-[review]/RESUME.md` da branch
`feature/epic-3-image-retention`. Rodada armada às 09:05, janela até **23:25**.

## Configuração desta rodada

Ver `unattended-run.md` nesta pasta. Resumo: 2 frentes em worktrees separados,
Opus 5 nas duas, 40 chamadas por frente, 2 rodadas de concílio no máximo,
1 build Gradle por vez, sem push, sem merge, sem deploy, sem aparelho de teste.

## Regime de manutenção descoberto

`nightly-reboot.timer` às 23:30 todo dia, confirmado por `last reboot` em 6 dias
seguidos. `/tmp` limpo às 23:51. Horizonte útil: 23:25.

## Por frente

### Frente A — EPIC 4 (#18) · `feature/epic-4-scheduled-messages`
- estado: iniciando
- por que parou: —

### Frente B — EPIC 3 (#17/#19, PR #20) · `feature/epic-3-image-retention`

- **estado final: revisão feita, correções commitadas, concílio de entrega concluído
  (2 rodadas, teto). A PR #20 NÃO foi mesclada e não deve ser mesclada como está.**
- commits: `29adb7e` (veredicto dos 8 leads), `97334de` (correções da revisão + teste),
  `1a5b570` (checkpoint), `35024cc` (correções da rodada 1 do concílio), `<este>` (fecho).
- revisão dos 8 leads herdados: 4 confirmados (1 BLOCKER, 2 MAJOR, 1 LOW), 3 descartados
  com evidência no código, 1 parqueado por depender do aparelho. Mais 1 achado fora dos
  leads (MAJOR: a captura de imagem bloqueava a de áudio — regressão proibida pela #17).
- concílio: 6 lentes, 2 rodadas. **31 achados levantados, 13 corrigidos, 5 viraram teste
  unitário (9 → 14, build verde), 10 vivos, 4 perguntas parqueadas.**

**Por que parou:** o teto de 2 rodadas foi atingido com achados vivos, e duas das correções
da rodada 1 abriram problema novo — as duas lentes da rodada 2 chegaram nelas
independentemente. Corrigir agora seria mudança sem verificação, e verificá-la exigiria a
terceira rodada que o contrato proíbe.

**O que ficou esperando o operador:**
1. decidir sobre os 10 achados vivos — em especial S1 (`SENDER_PREFIX` faz `"Ana: fotos"`
   voltar a disparar a varredura), S2 (janela/settle/retry contraditórios: a janela de 12 s
   é 9 s de fato, e a 1ª varredura só acha arquivo anterior à notificação) e S3
   (`DetailActivity` ainda usa a regra frouxa antiga);
2. `WhatsApp Images` tem subpasta de semana neste aparelho? (e o nome da pasta do Business);
3. a ordem de `EXTRA_MESSAGES` é do mais antigo para o mais novo neste aparelho?
4. o merge com `development` tem **conflito semântico que o git não acusa**:
   `isDuplicateRepost` (2 s, por texto) roda antes da extração de `EXTRA_PICTURE` e mata a
   notificação atualizada que traz o bitmap. E o conflito textual de imports do
   `NotifListenerService.kt` precisa dos **dois** lados — `--ours`/`--theirs` não compila.

Nenhum teste em aparelho nesta janela: `adb devices` vazio o tempo todo.
Detalhe completo: `docs/issues/017-epic-3-retencao-imagens-[review]/RESUME.md`.

## Esperando o operador

1. (nada ainda)
