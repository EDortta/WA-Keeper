# Execução não supervisionada — bloco de consentimento

Contrato-base: `~/Sync/Projects/AI/Agents/.docs/workflows/unattended-run.md`.
Este arquivo é o §1 daquele contrato aplicado ao WA-Keeper: os itens que precisam
estar **respondidos e guardados antes de armar qualquer coisa**.

> §0: "Uma execução autônoma que não sabe decidir parar não é autônoma. É um
> consumidor não supervisionado de orçamento."

Status: **NÃO ARMADO.** Itens marcados 🔴 aguardam o operador.

---

## §6 — Quando a máquina para (investigado, com evidência)

| Fato | Evidência |
|---|---|
| **Reboot obrigatório todo dia às 23:30** | `nightly-reboot.timer`: `OnCalendar=*-*-* 23:30:00`, `ExecStart=/home/esteban/scripts/nightly-reboot.sh` |
| O reboot realmente acontece | `last reboot`: boot às 23:36–23:37 em 25, 26, 27, 28, 29 e 31 de agosto — sem exceção na semana |
| Aviso de 5 min é interno ao script | unit: "aviso de 5 min + reboot" → trabalho útil precisa parar às **23:25** |
| `Persistent=true` foi removido de propósito | um slot perdido é pulado, não dispara reboot imediato na ativação |
| `/tmp` é limpo às 23:51 | `systemd-tmpfiles-clean.timer` — nada que o operador vá ler pode nascer em `/tmp` |

**Horizonte real: até 23:25 de hoje.** Não é "a noite toda". Depois do reboot,
qualquer agente in-process está morto — subagente não sobrevive a reboot nem ao
fechamento da sessão que o criou.

Isto é exatamente o anexo do contrato: *"aquela chamada começou 15 minutos antes
de um reboot diário que ela poderia ter consultado."* Aqui foi consultado.

## §1 — Carga da máquina (medida agora, 08:53)

| Recurso | Estado |
|---|---|
| CPU | 4 cores, load average **3,52 / 5,09 / 5,11** — já saturada antes de qualquer build |
| RAM | 15,9 GB total, **6,4 GB disponíveis** |
| Consumidores atuais | `node` 97,8% CPU, `beam.smp` 57%, `cadvisor` 25%, `chrome-headless` |

Dois builds Gradle simultâneos sobre load 5 não são paralelismo, são thrashing.
**Concorrência proposta: 1 build por vez**, serializado pelo `commit-ledger.md`,
mesmo com 2 agentes escrevendo código em paralelo.

---

## §1 — Itens de consentimento

| Item | Valor | Status |
|---|---|---|
| Repositórios que podem mudar | só `WA-Keeper` | proposto |
| Caminhos que podem mudar | `app/src/**`, `docs/**` nos **worktrees** `--epic4-scheduled-msg` e `--epic3-image-review`. Nunca o checkout principal, nunca `main` | proposto |
| Modelo por fase | 🔴 | **falta** |
| Teto por chamada / por issue / por rodada / por noite / campanha | 🔴 | **falta** |
| Concorrência de chamadas de modelo | 2 agentes | proposto |
| Concorrência de trabalho local | **1 build Gradle por vez** | proposto |
| Janela | agora → **23:25** (hard stop do reboot) | derivado do §6 |
| Canal de notificação + confirmação de entrega | 🔴 | **falta** |
| Revisão antes de chegar em `development` | por frente, antes da PR; concílio a cada 5 commits aprovados ou no delivery commit | do `README.md` desta pasta |

## §2 — Condições de parada

**Suspend** (transitório, com regra de retomada escrita ao lado):

| condição | retomada |
|---|---|
| janela fechada (23:25) | próxima janela, por ato do operador |
| pressão de recurso local (load > 6 sustentado) | quando cair por 3 verificações seguidas |

**Fault** (confiança no próprio estado acabou; só o operador libera):

- commit em `main`, push, deploy, ou qualquer publicação;
- escrita fora dos caminhos consentidos, inclusive no checkout principal;
- `development` deixado quebrado;
- sujeira de origem desconhecida na árvore de trabalho — **nunca** stash/clean automático;
- 2 rodadas de concílio sem fechar um achado;
- assinatura de rodada repetida (ciclo A→B→A) contra **qualquer** rodada anterior daquele objeto;
- **sem progresso**: 2 frentes consumiram trabalho real e não fecharam nada.

## §7 — O que nunca faz

`push`, deploy, merge para `development` ou `main`, tocar repo fora do consentido,
mexer em sujeira que não seja do próprio registro, seguir depois de violação de
fronteira, terceira rodada de concílio.

## §9 — Freios provados

**Nenhum.** Nada aqui foi exercitado ponta a ponta. O contrato é explícito:
*"Um freio não testado não existe."* As notas de projeto de um pipeline assim
(`~/Sync/Projects/AI/prompts/unattended-run-design-notes.md`) são guardadas como
referência e **não** como instrução — registro de quão difícil é a implementação,
não um plano validado.

Consequência: o que pode ser armado hoje é uma execução **com freios declarados e
não provados**, dentro de uma janela curta, com o operador como watchdog na volta.
Não é a esteira autônoma que o contrato descreve.
