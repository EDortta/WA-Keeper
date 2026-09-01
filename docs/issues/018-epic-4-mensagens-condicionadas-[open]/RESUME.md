# RESUME — EPIC 4: Mensagens condicionadas ao próximo contato

- work_id: epic4-scheduled-msg
- issues: #18 (épica; ainda sem filhas)
- date: 2026-09-01
- status: `[wip]` — passo zero concluído por leitura de código; implementação em curso
  na branch `feature/epic-4-scheduled-messages`.

## Passo zero — RESOLVIDO POR LEITURA (verificação em aparelho PARQUEADA)

**Conclusão: o mecanismo é viável, e o app já capta a ação de resposta hoje.**

`NotifListenerService.cacheReplyAction()` (linhas 237-248 antes deste trabalho) já
faz exatamente a metade difícil:

```kotlin
val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return
val remoteInputs = action.remoteInputs ?: return
val actionIntent = action.actionIntent ?: return
```

Ou seja: o `PendingIntent` de resposta direta e o array de `RemoteInput` do WhatsApp
já são extraídos e guardados por `"pacote|remetente"`, para `com.whatsapp` e
`com.whatsapp.w4b` (ambos em `watchedPackages`). O que nunca existiu no código é o
outro lado: **disparar** esse `PendingIntent`. O caminho documentado do Android é:

```kotlin
val intent = Intent()
val results = Bundle().apply { putCharSequence(resultKey, texto) }
RemoteInput.addResultsToIntent(remoteInputs, intent, results)
pendingIntent.send(context, 0, intent)   // lança PendingIntent.CanceledException se morreu
```

Fatos que sustentam a viabilidade, todos verificáveis sem aparelho:

| Fato | Origem |
|---|---|
| `Notification.Action.getRemoteInputs()` expõe os `RemoteInput` da resposta direta | API pública desde API 20; usada pelo código atual |
| `RemoteInput.addResultsToIntent(inputs, intent, bundle)` é o caminho oficial de preencher a resposta | `android.app.RemoteInput` — mesma classe já importada no listener |
| `PendingIntent.send()` roda com a identidade e as permissões do **WhatsApp**, não do WA-Keeper | semântica de `PendingIntent`; não exige permissão nova no manifest |
| Nenhuma permissão adicional é necessária — acesso a notificações já basta | manifest atual já tem `BIND_NOTIFICATION_LISTENER_SERVICE` |
| A restrição de *notification trampoline* do Android 12 atinge quem **publica** a notificação, não quem dispara o `PendingIntent` alheio | comportamento de `startActivity` a partir de broadcast/serviço; a resposta direta do WhatsApp é broadcast/serviço, não activity |
| O `PendingIntent` de resposta só vale enquanto a notificação está viva — e o gatilho da épica é justamente **uma notificação nova daquela conversa** | `invalidateReplyAction()` já limpa o cache em `onNotificationRemoved` |

Esse último ponto é o que faz a arquitetura fechar: a mensagem armada dispara no
exato instante em que uma notificação **fresca** daquela conversa acabou de ser
cacheada. Não dependemos de um `PendingIntent` velho.

### Limite honesto do que `send()` prova

`pendingIntent.send()` retornar sem exceção significa **"o Android aceitou entregar o
intent ao WhatsApp"** — não "o WhatsApp entregou a mensagem ao destinatário". A #18
pede `SENT` só depois de "o mecanismo de resposta do Android/WhatsApp **aceitar** o
envio", e é exatamente essa a fronteira que o código marca. O estado `SENT` deste
primeiro corte quer dizer *aceito pelo mecanismo*, e o RESUME registra isso em vez de
inflar a garantia. `PendingIntent.CanceledException` → `FAILED` com motivo registrado.

### Consequência de projeto

Como a verificação em aparelho é impossível nesta janela (`adb devices` vazio, operador
remoto), o envio entra **atrás da interface `ReplySender`**, com a implementação
`NotificationReplySender` isolada. Se o mecanismo tiver que mudar, troca-se a
implementação sem tocar na máquina de estados nem na UI — a épica não cai junto.

## Esperando o operador

1. **Verificar no aparelho** que a notificação do WhatsApp instalado ainda expõe uma
   `Notification.Action` com `remoteInputs` não vazio, e que disparar o `PendingIntent`
   com `RemoteInput.addResultsToIntent` de fato envia a mensagem — para `com.whatsapp`
   **e** para `com.whatsapp.w4b`, separadamente. O log `WAK-ReplyAction` já imprime
   `Reply action cached for <pkg>|<remetente> (key=<resultKey>)` quando a ação existe;
   o novo log `WAK-ScheduledMsg` imprime o resultado do disparo. Pergunta literal:
   *"A ação de resposta direta aparece no `adb shell dumpsys notification --noredact`
   para as duas contas, e o disparo entrega a mensagem no WhatsApp do destinatário?"*
2. **Nada nesta branch foi executado em aparelho.** O que está provado é teste unitário
   JVM da máquina de estados e da idempotência do claim. Nenhuma afirmação de
   funcionamento em dispositivo foi feita.

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
