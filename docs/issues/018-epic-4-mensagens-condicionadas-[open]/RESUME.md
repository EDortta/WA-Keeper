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

0. **Correção de rumo registrada:** o RESUME dizia antes que
   `PendingIntent.CanceledException` viraria `FAILED`. Não vira: vira `PENDING` com
   backoff, e `FAILED` só depois de esgotar as tentativas. A frase estava errada e foi
   corrigida aqui.

1. **Verificar no aparelho** que a notificação do WhatsApp instalado ainda expõe uma
   `Notification.Action` com `remoteInputs` não vazio, e que disparar o `PendingIntent`
   com `RemoteInput.addResultsToIntent` de fato envia a mensagem — para `com.whatsapp`
   **e** para `com.whatsapp.w4b`, separadamente. O log `WAK-ReplyAction` já imprime
   `Reply action cached for <pkg>|<remetente> (key=<resultKey>)` quando a ação existe;
   o novo log `WAK-ScheduledMsg` imprime o resultado do disparo. Pergunta literal:
   *"A ação de resposta direta aparece no `adb shell dumpsys notification --noredact`
   para as duas contas, e o disparo entrega a mensagem no WhatsApp do destinatário?"*
2. **`send()` sem exceção prova despacho, não entrega.** Se alguma build do WhatsApp
   rotear a resposta por uma activity, a restrição de *background activity launch* faz
   o disparo virar no-op silencioso — `send()` retorna normalmente e nada acontece. O
   overload de `PendingIntent.send` com `OnFinished` reduziria a dúvida e não foi usado:
   sem aparelho não havia como distinguir os casos. Pergunta literal: *"o disparo a
   partir do listener em segundo plano entrega, ou a ação de resposta do WhatsApp é
   `getActivity` e cai na restrição de background activity launch?"*

3. **A premissa "mensagem do `MessagingStyle` sem `Person` é do dono do aparelho"
   não foi confirmada.** Se alguma build do WhatsApp omitir o `Person` em conversa
   1-a-1, **toda** mensagem recebida seria classificada como eco e a épica nunca
   dispararia — em silêncio, sem erro. O log `WAK-ScheduledMsg` imprime
   `eco do próprio usuário ignorado (messages=…, history=…)` justamente para que isso
   apareça no `logcat`. Pergunta literal: *"em conversa 1-a-1, a última `Message` do
   `MessagingStyle` do WhatsApp tem `Person` preenchido?"*

4. **Escopo — decisão do operador, não do agente.** O concílio apontou que permitir
   **duas ou mais** mensagens armadas por conversa toca a evolução "múltiplas mensagens
   encadeadas", listada como fora do primeiro corte. A leitura desta implementação é
   que "encadeadas" significa *uma programação que dispara uma sequência*, e não *o
   usuário armar duas de forma independente* — cada linha continua sendo `once` e
   idempotente, e um gatilho entrega no máximo uma. Como o achado contraria uma decisão
   de projeto, ele **sai do concílio e vai ao operador** em vez de ser resolvido aqui.

5. **A branch `feature/epic-3-image-retention` saiu de `main`, não de `development`.**
   O `merge-base` das duas frentes é `main`. Consequência prática: um `git diff` da
   epic-3 contra `development` **mente** — parece apagar `CallDetector`, `Beeper`,
   `VoiceCommandEngine` e o cache de ações de resposta, que na verdade só não existem
   naquela base. Um `--ours/--theirs` apressado no merge apaga os comandos de voz. O
   merge em si é brando (dois conflitos de adjacência em `NotifListenerService.kt`),
   mas a base precisa ser corrigida antes.

6. **Lacunas conhecidas, não fechadas nesta janela:** não há lista global de mensagens
   armadas (só por conversa, alcançável pela `DetailActivity`) — e `Retention.purge`
   apaga as notificações sem tocar em `scheduled_messages`, então uma conversa purgada
   deixa a mensagem armada viva e sem caminho de UI; `exportSchema = false` impede
   teste automatizado da migration 4→5 (conferida coluna a coluna por leitura, não por
   `MigrationTestHelper`); a identidade da conversa é o título da notificação, então
   renomear o contato desvincula a mensagem em silêncio; a concorrência do claim é
   provada por simulação determinística, não por threads reais.

7. **Nada nesta branch foi executado em aparelho.** O que está provado é teste unitário
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

**Rodada 1** — 3 lentes distintas (idempotência/concorrência; fidelidade ao contrato
e honestidade; Android/integração), rodadas contra os commits `a29b3d5..36a8d63`,
sem que um membro visse a saída do outro.

| contagem | valor |
|---|---|
| achados levantados | **24** (8 por lente) |
| sobreviveram à triagem | **21** (3 eram pontos que as próprias lentes verificaram e passaram) |
| fechados com correção nesta rodada | **11** |
| viraram teste | **10 testes novos** (5 no coordenador, 5 em `OwnMessageHeuristicTest`) |
| parqueados para o operador | **10** |

Os 11 fechados: claim preso ressuscitando mensagem já entregue (BLOCKER); contador de
linhas do `markSent`/`markFailed` descartado (envio real virava linha `PENDING`);
resgate de claim preso que nunca alcançava o caso comum; TOCTOU entre a leitura e o
claim fazendo sair texto já editado; heurística de eco da própria resposta com as duas
pernas mutuamente excludentes; `FakeStore` divergente do SQL real em 4 pontos;
`NO_ACTION` queimando cota de tentativas e matando a mensagem por condição transitória;
textos de UI e KDoc alegando entrega que o mecanismo não prova; linha `FAILED` sem saída
na interface; `AlertDialog` sem `dismiss`.

- Cadência: a cada 5 commits aprovados, ou no delivery commit — o que vier primeiro.
- Teto: 2 rodadas. Achado vivo depois da rodada 2 vai ao operador.
