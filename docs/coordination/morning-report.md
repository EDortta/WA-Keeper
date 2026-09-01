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
- estado: iniciando
- por que parou: —

### Frente B — EPIC 3 (#17/#19, PR #20) · `feature/epic-3-image-retention`
- estado: **encerrada 09:28. PR #20 NÃO está pronta para merge.** Nada mesclado,
  nada empurrado, árvore limpa, 5 commits novos na branch.
- por que parou: **teto de 2 rodadas de concílio atingido com 10 achados vivos** —
  e duas correções da rodada 1 abriram problema novo, o que o contrato manda ler
  como sinal de parar, não de corrigir de novo. Terceira rodada é proibida.

**Verificado pelo coordenador (não só relatado):** árvore limpa, 5 commits em
`feature/epic-3-image-retention`, e `:app:testDebugUnitTest` com **14 testes,
0 falhas, 0 erros** (`MediaHintsTest`).

Concílio: 6 lentes, 2 rodadas, **31 achados levantados**, 13 corrigidos (todos na
rodada 1, 12 verificados fechados na rodada 2), **5 viraram teste**, **10 vivos**.

Dos 8 leads do briefing: 4 confirmados (1 BLOCKER — `containsMatchIn` fazia
"me manda a foto" disparar a varredura; 2 MAJOR; 1 LOW), 3 descartados com
evidência, 1 parqueado por depender do aparelho. Um achado fora dos leads: a
captura de imagem era aguardada **à frente** da de áudio na mesma coroutina,
atrasando o áudio em até 10,5 s — regressão que a #17 proíbe.

## Esperando o operador

**Da frente B**, na ordem de tratar:

1. **Decidir sobre os 10 achados vivos.** Os dois que a rodada 2 achou de forma
   independente, por duas lentes: `stripSenderPrefix` (adicionado para não perder
   notificação de grupo) devolve o falso positivo — `"Ana: fotos"` volta a
   disparar a varredura numa mensagem de texto; e `FILE_SETTLE_MS` contradiz a
   escada de retry, deixando a janela declarada de 12 s valer 9 s de fato. Há
   recomendação escrita para cada um, nenhuma executada.
2. **Conflito semântico no merge que o git não acusa.** `development` ganhou
   `isDuplicateRepost` (2 s, por texto) que roda **antes** da extração de
   `EXTRA_PICTURE` e mataria a notificação atualizada que traz o bitmap. O
   conflito textual de imports em `NotifListenerService.kt` precisa dos **dois**
   lados — `--ours`/`--theirs` não compila.
3. **Perguntas que só o aparelho responde:** `WhatsApp Images` tem subpasta de
   semana? Qual o nome da pasta do Business? `EXTRA_MESSAGES` vem do mais antigo
   para o mais novo neste aparelho? O código foi endurecido para funcionar com ou
   sem subpasta, sem supor qual.
