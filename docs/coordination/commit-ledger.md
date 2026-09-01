# Ledger de commits — execução paralela

Uma linha por commit aprovado, por frente. A contagem de commits aprovados é o
que dispara o concílio a cada 5 (ver `README.md` desta pasta).

## Uso do aparelho de teste (recurso exclusivo)

| Quem | Desde | Até | Nota |
|---|---|---|---|
| — | — | — | livre |

## Frente A — EPIC 4 (#18) · `feature/epic-4-scheduled-messages`

| # | commit | o quê | crítica por commit | conta p/ concílio |
|---|---|---|---|---|
| 1 | `a29b3d5` | passo zero: viabilidade do `RemoteInput`+`PendingIntent` decidida por leitura | auto-crítica: conclusão é de leitura, não de aparelho — registrado como pendência explícita, sem alegar funcionamento | 1/5 |
| 2 | `08abf31` | tabela `scheduled_messages` + DAO com claim atômico + `ScheduledMessageStore` + migration 4→5 | auto-crítica: (a) índice do `@Entity` e o da migration precisam ter o **mesmo nome**, senão o Room recusa o banco migrado — nomeado `index_scheduled_messages_conversation` nos dois lados; (b) compilação ainda não verificada, o build vem junto com o commit do coordenador+testes | 2/5 |
| 3 | `a5bc9e5` | `ReplySender` (interface) + `NotificationReplySender` + `ReplyActionRegistry` extraído do listener | auto-crítica: toca `NotifListenerService.kt`, que é da frente B — o diff foi mantido em 3 trechos contíguos (campo removido, 2 métodos viram 1 delegação, 1 linha em `onNotificationRemoved`), longe do `onNotificationPosted` que a frente B mexe. Sem mudança de comportamento neste commit | 3/5 |
| 4 | (este) | `ScheduledMessageCoordinator` + `ScheduledMessageTrigger` + gancho no listener + 12 testes JVM | **build verde**: `:app:testDebugUnitTest` → 35 testes, 0 falhas (12 novos). O KSP do Room valida o SQL do DAO em tempo de compilação, então as queries do claim estão conferidas. Auto-crítica: (a) a corrida do claim é reproduzida por hook determinístico, não por threads — o que se prova é a **semântica** do `UPDATE` condicional, e o Room/SQLite é quem garante a serialização de verdade; (b) `looksLikeOwnMessage` depende de `MessagingStyle` e **não tem teste** — precisa de Robolectric ou aparelho, ficou como pendência | 4/5 |

Nota: a branch estava 3 commits atrás de `development` (só docs de coordenação).
Fast-forward `cf9779a..b55378b` aplicado no próprio worktree para que `RESUME.md` e
este ledger existissem na branch. Nenhum merge de trabalho, nenhum push.

## Frente B — EPIC 3 (#17/#19) · `feature/epic-3-image-retention`

| # | commit | o quê | crítica por commit | conta p/ concílio |
|---|---|---|---|---|
| 1 | `8abca07` | add image media fallback capture (#19) | pendente — herdado, revisar | — |
| 2 | `bce7983` | allow late image path attachment (#19) | pendente — herdado, revisar | — |
| 3 | `fa4b660` | persist WhatsApp images beyond notification bitmap (#19) | pendente — herdado, revisar | — |

Os três commits da frente B chegaram prontos, sem crítica registrada. A revisão
da PR #20 cobre os três de uma vez e vale como a crítica que faltou; o concílio
da frente B é o da entrega (delivery commit), antes do merge.
