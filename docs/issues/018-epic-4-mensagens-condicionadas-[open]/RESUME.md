# RESUME — EPIC 4: Mensagens condicionadas ao próximo contato

- work_id: epic4-scheduled-msg
- issues: #18 (épica; ainda sem filhas)
- date: 2026-09-01
- status: `[open]` — nada implementado. Worktree e branch criados.

## Next Step (DO THIS FIRST)

Antes de escrever código: decidir e registrar aqui se o WhatsApp instalado neste
aparelho **expõe** ação de resposta compatível (`RemoteInput` + `PendingIntent`)
na notificação. Toda a épica depende disso. A issue é explícita: se a versão não
expuser ação compatível, **registrar a impossibilidade — não simular sucesso**.

Esse é o mesmo mecanismo que a Fase 4 dos comandos de voz (#8, responder por voz)
nunca chegou a implementar. Se ele funcionar aqui, a #8 depois vira só a camada
de ditado por cima.

## O que a issue pede

Armar uma mensagem para uma conversa (`packageName + sender`) e entregá-la
**uma única vez** quando aquela pessoa/grupo voltar a mandar mensagem. Não é
saudação genérica nem mensagem periódica.

Contrato do primeiro corte:
- estados `PENDING / CLAIMED(SENDING) / SENT / FAILED / CANCELLED`;
- transição `PENDING -> CLAIMED` **atômica**, para que notificação repetida ou
  agrupada não gere envio duplo;
- `SENT` só depois de o mecanismo de envio aceitar — nunca antes;
- falha volta a estado recuperável com tentativa registrada, sem loop apertado;
- cancelar/desarmar possível antes do contato chegar;
- mensagem que disparou continua sendo persistida normalmente;
- mensagem enviada pelo próprio usuário não conta como gatilho;
- guardar `triggerNotificationKey`, timestamps e resultado para auditoria;
- funciona separadamente para `com.whatsapp` e `com.whatsapp.w4b`.

Fora do primeiro corte (não implementar): expiração, mensagens encadeadas,
regras recorrentes, horário permitido, condições semânticas, ausência/boas-vindas.

## Concílio

- Rodadas: **0**. Achados levantados: —. Sobreviventes: —. Viraram teste: —.
  Perguntas abertas: —.
- Cadência: a cada 5 commits aprovados, ou no delivery commit — o que vier primeiro.
