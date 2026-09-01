# Coordenação da execução paralela

Esta pasta existe porque a implementação do backlog roda com **mais de um agente
ao mesmo tempo** nesta máquina. O protocolo do operador para execução paralela
exige que a fila, o ledger de commits, as críticas por commit e os concílios
fiquem registrados — não na cabeça de quem coordena.

## Contratos que valem aqui

O contrato-base do concílio é kit-owned e vive fora deste repo:
`~/Sync/Projects/AI/Agents/.docs/agents/council.md`. Os pontos que este repo
precisa obedecer:

| Regra | Valor |
|---|---|
| Momento padrão do concílio | **delivery commit** — o commit que fecha o trabalho e precede a devolução ao operador |
| Cadência em execução paralela | **a cada 5 commits aprovados**, ou no fim da frente, o que vier primeiro |
| Teto de rodadas | **2**. Achado vivo depois da rodada 2 → para e vai ao operador. Nunca uma 3ª rodada |
| Ordem | concílio roda **depois** da revisão retornar não-BLOCKER, contra o artefato **aprovado**. Rodar concílio no lugar da revisão é `[PROHIBITED]` |
| Lentes | cada membro recebe uma lente distinta; um membro não vê a saída do outro |
| O concílio produz | **achados, nunca decisões**. Não modifica código |
| Achado que contraria regra estabelecida do projeto | **sai do concílio** e vai ao operador |
| Registro | obrigatório: quantos achados levantados, quantos sobreviveram, quantos viraram teste, quantas perguntas ficaram abertas — no `RESUME.md` da issue e no `docs/napkin-lessons.md` |

Este repo **não tem** hook de gate para registro machine-readable do concílio.
As contagens ficam nos `RESUME.md`, como no CodexBridge sem hook.

## Estado das frentes

| Frente | Issue | Worktree | Branch | Agente |
|---|---|---|---|---|
| EPIC 4 — mensagens condicionadas | #18 | `../WA-Keeper--epic4-scheduled-msg` | `feature/epic-4-scheduled-messages` | A |
| EPIC 3 — retenção de imagens | #17 / #19 (PR #20) | `../WA-Keeper--epic3-image-review` | `feature/epic-3-image-retention` | B |

Ordem de prioridade: issue **#21 (ROADMAP)**. EPIC 4 e EPIC 3 vêm antes de
Godofredo (#1) e Áudio Seguro (#2); nenhuma feature lateral ultrapassa as duas
enquanto não estiverem utilizáveis.

## Regras de convivência

- Cada agente commita **só na sua branch**, dentro do **seu** worktree.
- `development` é a branch de integração; `main` é só o recorte de deploy.
  Nenhum agente commita em `main`, e nenhuma PR aponta para `main`.
- Merge para `development` e qualquer deploy: **decisão do operador**, nunca do agente.
- **Um único telefone de teste**, que é o aparelho pessoal do operador. Validação
  por `adb`/uiautomator **serializa**: quem for usar o aparelho anuncia no
  `commit-ledger.md` antes, e libera depois. Se aparecer diálogo do sistema não
  relacionado (alarme, ligação, lembrete de medicação), **parar de tocar na tela**
  e esperar — nunca tocar coordenada às cegas.
- Build Gradle é pesado e a máquina tem 4 cores / ~5 GB livres: **no máximo 2
  builds simultâneos**. Se os dois agentes precisarem buildar ao mesmo tempo,
  serializar pelo ledger.
