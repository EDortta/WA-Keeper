# Execução não supervisionada — bloco de consentimento

Contrato-base: `~/Sync/Projects/AI/Agents/.docs/workflows/unattended-run.md`.
Este arquivo é o §1 daquele contrato aplicado ao WA-Keeper: os itens que precisam
estar **respondidos e guardados antes de armar qualquer coisa**.

> §0: "Uma execução autônoma que não sabe decidir parar não é autônoma. É um
> consumidor não supervisionado de orçamento."

Status: **ARMADO** em 2026-09-01 09:05, por consentimento explícito do operador
("pode proceder"), com a configuração da tabela §1 abaixo. O operador está
**remoto e ausente**, e avisou que **a energia pode cair**.

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
| Repositórios que podem mudar | só `WA-Keeper` | **consentido** |
| Caminhos que podem mudar | `app/src/**`, `docs/**` nos **worktrees** `--epic4-scheduled-msg` e `--epic3-image-review`. Nunca o checkout principal, nunca `main` | **consentido** |
| Modelo por fase | Opus 5 (`claude-opus-5`) em implementação e revisão | **consentido** |
| Teto por chamada / por issue / por rodada / por noite / campanha | 40 chamadas por frente; 2 rodadas de concílio; noite = a janela até 23:25; campanha = esta janela | **consentido** |
| Concorrência de chamadas de modelo | 2 agentes | **consentido** |
| Concorrência de trabalho local | **1 build Gradle por vez**, via `flock` em `~/.local/state/ai-agents/wa-keeper-build.lock` | **consentido** |
| Janela | agora → **23:25** (hard stop do reboot) | derivado do §6 |
| Canal de notificação + confirmação de entrega | `docs/coordination/morning-report.md`, versionado e commitado — sobrevive a queda de energia e à limpeza de `/tmp`. Não há canal externo configurado neste repo | **consentido** |
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


---

## Queda de energia — o que muda

O operador está remoto e a energia pode cair. Um agente in-process morre junto
com a máquina, sem aviso e sem chance de fazer checkpoint. O contrato pede
relatório "durável o bastante para sobreviver ao crash que o produziu"; aqui a
única durabilidade real é **git**.

Regra imposta às duas frentes:

- **Commit pequeno e frequente na própria branch.** Um crash pode custar no
  máximo um incremento de trabalho, nunca uma noite.
- **`RESUME.md` e `commit-ledger.md` atualizados no mesmo commit do código**, não
  depois. Estado que só existe na cabeça do agente não sobrevive à queda.
- **Nada de trabalho longo sem commit intermediário.** Se uma etapa não cabe em
  um commit, ela é grande demais.
- Na retomada: o `RESUME.md` de cada frente é a fonte de verdade do que já foi
  feito. `git log` da branch é a segunda.

## Alavanca de potência — containers

O operador autorizou desarmar os containers para liberar máquina, no máximo a
cada 30 minutos, se a potência faltar.

**42 containers rodando agora** (`jk-structure-*` da ZeeCred, `lct-acheivc-*`,
observabilidade) — é o que produz o load ~5.

⚠️ O script indicado, `~/Sync/Authfy/authfy-docker/stop-all-containers.sh`, faz
`docker stop` **e `docker rm`** sobre `docker ps -a`: ele **remove** os
containers, não só desliga. Para stack gerida por compose isso é recuperável com
um `up` — para o resto, não é.

**Decisão desta rodada:** usar `docker stop` **sem** `rm`, e só sobre o que for
preciso. É reversível com `docker start` e entrega o mesmo alívio de CPU. O
script com `rm` fica como botão do operador, não do agente.

Nota: `lct-acheivc-backend-*` subiu há 1 minuto e o `crontab` tem guardiões
(`wa-hub` a cada 2 min) que recriam containers — parar tudo pode virar briga com
guardião, não alívio.

## Aparelho de teste — indisponível nesta rodada

`adb devices` está vazio e o operador está fisicamente longe do aparelho.
**Nenhuma verificação em dispositivo é possível nesta janela.** Consequência:

- vale teste unitário JVM e leitura estática;
- qualquer achado que só o aparelho confirma (ex.: o caminho real de
  `WhatsApp Images`, se tem subpasta) é **parqueado** como `needs_operator`,
  com a pergunta escrita literalmente no `RESUME.md` da frente — nunca resolvido
  por suposição.
