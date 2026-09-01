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
| — | — | (nada ainda) | — | 0/5 |

## Frente B — EPIC 3 (#17/#19) · `feature/epic-3-image-retention`

| # | commit | o quê | crítica por commit | conta p/ concílio |
|---|---|---|---|---|
| 1 | `8abca07` | add image media fallback capture (#19) | pendente — herdado, revisar | — |
| 2 | `bce7983` | allow late image path attachment (#19) | pendente — herdado, revisar | — |
| 3 | `fa4b660` | persist WhatsApp images beyond notification bitmap (#19) | pendente — herdado, revisar | — |

Os três commits da frente B chegaram prontos, sem crítica registrada. A revisão
da PR #20 cobre os três de uma vez e vale como a crítica que faltou; o concílio
da frente B é o da entrega (delivery commit), antes do merge.
