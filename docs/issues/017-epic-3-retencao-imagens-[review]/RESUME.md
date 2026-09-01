# RESUME — EPIC 3: Retenção de imagens recebidas no WhatsApp

- work_id: epic3-image-review
- issues: #17 (épica), #19 (`IMG-001`), PR #20
- date: 2026-09-01
- status: `[review]` — revisão dos 3 commits herdados **em curso** na branch
  `feature/epic-3-image-retention`, worktree `../WA-Keeper--epic3-image-review`.
- aparelho de teste: **indisponível nesta janela** (`adb devices` vazio, operador
  remoto). Nada foi verificado em dispositivo.

## Next Step (DO THIS FIRST)

Se a rodada tiver caído aqui: ver "Estado da revisão" abaixo, seguir do primeiro
lead ainda marcado `pendente`. Depois da revisão fechar não-BLOCKER, rodar o
concílio de entrega (2 rodadas, teto). Merge é decisão do operador.

## O que as issues pedem

`EXTRA_PICTURE` não é confiável para toda notificação de imagem, então a linha da
mensagem persiste com `imagePath` nulo e a imagem some quando o WhatsApp a apaga.
A #19 manda adicionar captura best-effort no `MediaVault` a partir do diretório
oficial de mídia, com `EXTRA_PICTURE` continuando como fonte preferencial,
dedup pelo arquivo-fonte, retry curto com backoff, sem mexer no schema
(`imagePath` já existe) e **sem regressão no áudio**.

## Entregue antes desta revisão (3 commits, +133/−14)

| commit | o quê |
|---|---|
| `8abca07` | captura de imagem via diretório de mídia (`MediaVault.captureLatestImage`) |
| `bce7983` | anexar caminho de imagem tardiamente (`NotifDao.setImagePath`) |
| `fa4b660` | persistir imagem além do bitmap da notificação (URI de `MessagingStyle`) |

Três caminhos em cascata no `NotifListenerService`: `EXTRA_PICTURE` →
URI de `MessagingStyle` (`captureImageUri`) → varredura do diretório de mídia
com retry `300/1200/3000/6000 ms` (`captureLatestImage`).

## Estado da revisão — os 8 leads do briefing

Cada um confirmado ou descartado **com evidência no código**, nunca aceito por
estar escrito no briefing.

| # | lead | veredicto | evidência |
|---|---|---|---|
| 1 | falso positivo de imagem em texto | **CONFIRMADO — BLOCKER** | `IMAGE_HINT` casa `foto\|imagem\|image\|photo\|picture\|fotografia` com `containsMatchIn`, em qualquer posição. `isImage` entra em `captureImage`, que anexa **qualquer** arquivo da pasta dentro de `[t−15 s, t+8 s]`. Viola a restrição literal da #17: *"Não anexar imagem antiga a uma mensagem de texto por coincidência temporal."* |
| 2 | regressão no áudio por `key()` | **DESCARTADO** | `key` passou de `name:lastModified` para `absolutePath:lastModified`. `absolutePath` é estritamente **mais** discriminante que `name`: só pode produzir *menos* acertos de dedup, nunca mais — logo nunca suprime uma captura legítima. O set `consumed` é compartilhado, mas as chaves são caminhos absolutos sob raízes disjuntas (`WhatsApp Voice Notes` vs `WhatsApp Images`): não há colisão cruzada possível. O `@Synchronized` novo em `captureLatest` **corrige** uma corrida de check-then-add que existia antes. |
| 3 | `captureImageUri` não deduplica e colide | **CONFIRMADO — MAJOR** | destino é `img-uri-$aroundTs$ext`, sem discriminador. Os outros dois caminhos têm um (`img-$postTime-${bmp.hashCode()}.jpg`, `img-$aroundTs-${candidate.name}`). Duas notificações com o mesmo `postTime` (resumo + filha de grupo) apontam duas linhas para o **mesmo arquivo**, com o conteúdo de quem escreveu por último. |
| 4 | dedup só em memória | **CONFIRMADO — LOW, não é defeito novo** | `consumed` é `ConcurrentHashMap.newKeySet()` em processo. Vale igual para o áudio desde antes desta branch: é a propriedade existente, não uma regressão. O dano é limitado pela janela (±15 s / 8 s do `postTime` novo). Sem correção — anotado. |
| 5 | caminho do diretório pode estar errado | **PARQUEADO — só o aparelho confirma** | estático: o caminho de voz percorre subpastas de semana (`listFiles { isDirectory }`), o de imagem lista **só a raiz**. Se esta versão do WhatsApp usar subpasta em `WhatsApp Images`, o fallback nunca acha nada. Não dá para decidir sem o aparelho. **Mitigado sem supor**: a varredura passou a cobrir raiz **e** as subpastas mais recentes, o que é correto nos dois mundos. A pergunta literal continua aberta (ver "Esperando o operador"). |
| 6 | janela × retry inconsistentes | **CONFIRMADO — MAJOR** | os esperas somam `300+1200+3000+6000 = 10 500 ms` depois do `postTime`, mas `IMAGE_WINDOW_FORWARD_MS = 8 000`. Um arquivo que aterrisse em t+9 s é procurado pelo 4º retry e recusado pela janela: os últimos ~2,5 s de retry são caminho morto. |
| 7 | limpeza de órfãos cobre `img-*` e `img-uri-*` | **DESCARTADO — DoD atendida** | `Retention.sweepOrphanImages` faz `imageDir(ctx).listFiles()` e apaga tudo que não estiver em `dao.allImagePaths()`. É agnóstico a prefixo, e os dois caminhos novos gravam em `Retention.imageDir(ctx)` e registram via `setImagePath`. Nada a corrigir. |
| 8 | degradação sem `MANAGE_EXTERNAL_STORAGE` | **DESCARTADO — DoD atendida** | `captureLatestImage` começa com `if (!hasAllFilesAccess()) return null`. `captureImageUri` não precisa da permissão (lê `content://` pelo `ContentResolver`, com o grant que o `NotificationListenerService` recebe) e está dentro de `runCatching{}.getOrNull()`, então `SecurityException`/`FileNotFoundException` viram `null`. `extractMessagingStyleImage` idem. `getMessagesFromBundleArray` é API 24 e o `minSdk` é 26. Sem crash nos dois caminhos. |

### Achado fora dos 8 leads

| # | achado | severidade | evidência |
|---|---|---|---|
| 9 | **captura de imagem serializa na frente da captura de áudio** | **MAJOR — regressão no áudio, proibida pela #17/#19** | no `onNotificationPosted`, `captureImage(...)` é chamado com `await` implícito dentro da mesma coroutine, **antes** de `captureAudio(...)`. Ele bloqueia por até 10,5 s quando não acha nada. Uma notificação que casa o hint de imagem e é de voz empurra a captura de áudio para t+10,8 s…t+21,3 s, dando ao WhatsApp mais tempo de apagar o `.opus`. Antes desta branch o áudio começava em t+0,3 s. |

## Correções aplicadas

Ver `docs/coordination/commit-ledger.md` para o commit de cada uma.

## Concílio

- Rodadas: **0**. Achados levantados: —. Sobreviventes: —. Viraram teste: —.
  Perguntas abertas: —.
- Pendente: concílio de entrega, depois da revisão fechar não-BLOCKER.

## Esperando o operador

1. **Lead 5 — `WhatsApp Images` tem subpasta neste aparelho?**
   Pergunta literal: *no seu telefone, `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images`
   guarda os `.jpg` direto na raiz, ou dentro de uma subpasta com nome tipo
   `202635` (ano+semana), como acontece em `WhatsApp Voice Notes`?* O código foi
   endurecido para funcionar nos dois casos, mas a resposta decide se a varredura
   da raiz é código morto.
2. **Lead 5b — nome da pasta do WhatsApp Business.** O código assume
   `Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images`,
   por simetria com o caminho de voz que já estava lá. Não foi verificado em
   aparelho. Vale a mesma pergunta.
3. **Merge da PR #20.** Decisão do operador; nenhum agente mescla.
