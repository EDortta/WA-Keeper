# Briefing — Agente A · EPIC 4 (#18)

**Worktree:** `/home/esteban/Sync/Projects/WA-Keeper--epic4-scheduled-msg`
**Branch:** `feature/epic-4-scheduled-messages` (base `development`)
**Entregável:** primeiro corte de mensagens condicionadas ao próximo contato, em PR
para `development`.

## Passo zero, antes de qualquer código

Verificar se o WhatsApp instalado **expõe ação de resposta compatível**
(`RemoteInput` + `PendingIntent`) na notificação, para `com.whatsapp` e para
`com.whatsapp.w4b`. Registrar o resultado no `RESUME.md` da issue 018.

A #18 é explícita: se a versão não expuser ação compatível, **registrar a
impossibilidade — não simular sucesso**, e nada de automação de tela. Se esse
passo falhar, pare e leve ao operador: a épica inteira depende dele, e o mesmo
mecanismo é o que a #8 (responder por voz) vai precisar depois.

## Escopo do primeiro corte

- Regra vinculada a `packageName + sender`; gatilho é nova notificação daquela conversa.
- Entrega `once`: uma única tentativa, nunca reprograma sozinha.
- Estados `PENDING / CLAIMED(SENDING) / SENT / FAILED / CANCELLED`.
- **Claim atômico** — `PENDING -> CLAIMED` só para uma execução. Notificação
  repetida, agrupada ou atualizada não pode gerar segundo envio.
- `SENT` só depois de o envio ser aceito. Falha volta a estado recuperável, com
  tentativa registrada, sem loop apertado.
- Mensagem do próprio usuário não é gatilho.
- A mensagem recebida que disparou continua sendo persistida normalmente.
- Auditoria: `triggerNotificationKey`, timestamps, resultado do envio.
- UI mínima: a partir da conversa, escrever a mensagem e escolher o equivalente a
  **"Enviar quando esta pessoa falar comigo"**; lista das armadas permite editar
  e cancelar enquanto pendentes.

**Fora do primeiro corte, não implementar:** expiração/validade, mensagens
encadeadas, regras recorrentes, horário permitido, condições semânticas,
políticas de ausência/boas-vindas. A issue avisa que essas extensões não podem
enfraquecer o contrato `once` nem a idempotência — então não as antecipe.

## Onde o código encosta

`NotifListenerService.kt` é o ponto de entrada do gatilho — e é o **mesmo arquivo**
que a frente B está mexendo. Toque o mínimo possível nele e prefira concentrar a
lógica nova em arquivo próprio, para o merge das duas frentes não virar conflito.
`NotifDatabase.kt` (Room) é onde a nova tabela de mensagens armadas entra.

## Ritmo e concílio

- Crítica por commit (até 3 lentes), registrada no `docs/coordination/commit-ledger.md`.
- **Concílio a cada 5 commits aprovados**, ou no delivery commit — o que vier
  primeiro. Teto de 2 rodadas; achado vivo depois da rodada 2 vai ao operador.
  Rodada 2 classifica cada achado como *antigo*, *pré-existente recém-visto* ou
  *introduzido por correção da rodada 1*.
- Registrar contagens no `RESUME.md` da issue 018 e no `docs/napkin-lessons.md`.

## Restrições

- Aparelho de teste é **um só** e é o telefone pessoal do operador. Anuncie o uso
  no ledger antes de conectar; se aparecer diálogo de sistema não relacionado,
  pare de tocar na tela e espere.
- Máximo 2 builds Gradle simultâneos na máquina.
- Commits só nesta branch, neste worktree. Nada em `main`. Merge e deploy são do operador.
