# Relatório da rodada — 2026-09-01

Status: **rodada encerrada** às 09:35. As duas frentes pararam no teto de 2
rodadas de concílio — nenhuma por falta de tempo, nenhuma por falha de ambiente.

## Primeira linha (§8)

**A EPIC 4 (#18) foi implementada e testada; a EPIC 3 (PR #20) não está pronta
para merge.** Nada foi mesclado, empurrado ou publicado. As duas frentes deixaram
achados vivos que precisam da sua decisão: **19 no total** (10 da B, 9 da A).

A janela ia até 23:25; as frentes terminaram às 09:28 e 09:35, muito antes.

## Configuração desta rodada

Ver `unattended-run.md` nesta pasta. Resumo: 2 frentes em worktrees separados,
Opus 5 nas duas, 40 chamadas por frente, 2 rodadas de concílio no máximo,
1 build Gradle por vez, sem push, sem merge, sem deploy, sem aparelho de teste.

## Regime de manutenção descoberto

`nightly-reboot.timer` às 23:30 todo dia, confirmado por `last reboot` em 6 dias
seguidos. `/tmp` limpo às 23:51. Horizonte útil: 23:25.

## Por frente

### Frente A — EPIC 4 (#18) · `feature/epic-4-scheduled-messages`
- estado: **primeiro corte implementado e testado.** 8 commits, sem PR, sem merge.
- por que parou: teto de 2 rodadas de concílio, com 9 achados vivos.

**Verificado pelo coordenador:** 8 commits em `feature/epic-4-scheduled-messages`,
**47 testes JVM, 0 falhas** (`ScheduledMessageCoordinatorTest` 19,
`OwnMessageHeuristicTest` 5, mais os 23 de voz que já existiam).

**O passo zero achou mecanismo que já estava no app:**
`NotifListenerService.cacheReplyAction()` já capturava o `PendingIntent` de
resposta direta e os `RemoteInput` do WhatsApp, nas duas contas. Faltava só o
outro lado (`RemoteInput.addResultsToIntent` + `send`). Sem permissão nova, sem
automação de tela. O limite ficou escrito: `send()` sem exceção prova **despacho,
não entrega** — por isso o envio ficou atrás da interface `ReplySender`.

Entregue: tabela `scheduled_messages` (migration 4→5) com claim atômico via
`UPDATE ... WHERE state='PENDING'`; `ScheduledMessageCoordinator` sem dependência
de Android (por isso testável); `ReplySender`/`NotificationReplySender`;
`ReplyActionRegistry`; `ScheduledMessageTrigger` como gancho único; UI de armar,
listar, editar e cancelar. O toque em `NotifListenerService.kt` ficou em três
trechos pequenos, longe do `onNotificationPosted` que a frente B mexe.

Concílio: **42 achados levantados** (24 + 18), 20 fechados, 13 viraram teste.
Na rodada 2, **11 dos 18 achados foram introduzidos pelas correções da rodada 1** —
proporção alta, que o contrato manda ler como razão para parar. Um deles era
blocker de contrato: ao devolver a tentativa no caso "notificação sem ação de
resposta", `attempts` voltava a zero e a UI só mostra erro com `attempts > 0` —
a impossibilidade que a #18 manda registrar sumia da tela, com três KDocs
afirmando o oposto.

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
**Da frente A:**

4. **Os 9 achados vivos** da rodada 2, no `RESUME.md` da 018.
5. **Confirmar no aparelho** que a ação de resposta existe nas duas contas. Nenhuma
   linha foi verificada em dispositivo nesta rodada, e nenhum documento afirma o
   contrário.
6. **Decisão de produto:** armar duas mensagens para a mesma conversa fere
   "mensagens encadeadas", que a #18 põe fora do primeiro corte? Achado que
   contraria decisão de projeto **saiu do concílio** e veio para você, como o
   contrato manda.

**Do coordenador:**

7. **A branch da frente B está 18 commits atrás de `development`.** Ela nasceu de
   `fe713e0`, que é exatamente a ponta atual de `main` — daí a impressão de que
   "saiu de main". Não é história divergente (`fe713e0` é ancestral de
   `development`), então o merge é normal; o perigo é a resolução de conflito:
   um `--ours`/`--theirs` apressado apaga os 18 commits de comandos de voz.
8. **`.gitignore` não cobria `.credentials/` nem `.env`.** O helper `awt` cria,
   dentro do worktree, um `.credentials/store` que é **symlink para
   `~/.config/credentials/personal`**. Um `git add -A` distraído versionaria o
   ponteiro para o cofre pessoal. Fechado nesta rodada, na seção SEC-0066. As
   duas frentes deixaram os arquivos intocados e reportaram — comportamento certo.

3. **Perguntas que só o aparelho responde:** `WhatsApp Images` tem subpasta de
   semana? Qual o nome da pasta do Business? `EXTRA_MESSAGES` vem do mais antigo
   para o mais novo neste aparelho? O código foi endurecido para funcionar com ou
   sem subpasta, sem supor qual.
