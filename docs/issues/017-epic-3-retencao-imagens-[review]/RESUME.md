# RESUME — EPIC 3: Retenção de imagens recebidas no WhatsApp

- work_id: epic3-image-review
- issues: #17 (épica), #19 (`IMG-001`), PR #20
- date: 2026-09-01
- status: `[review]` — revisão e concílio de entrega **encerrados** em 2026-09-01 09:28,
  no teto de 2 rodadas. Branch `feature/epic-3-image-retention` (worktree
  `../WA-Keeper--epic3-image-review`): 5 commits, 14 testes JVM verdes, árvore limpa.
  **PR #20 NÃO está pronta para merge** — 10 achados vivos. Nada mesclado nem empurrado.
- aparelho de teste: **indisponível nesta janela** (`adb devices` vazio, operador
  remoto). Nada foi verificado em dispositivo.

## Next Step (DO THIS FIRST) — decisão do operador

Nenhum agente continua daqui sem sua decisão. Na ordem:

1. Decidir sobre os **10 achados vivos** da rodada 2 (S1…S10 abaixo). S1 e S2 regridem
   a restrição central da #17 e por isso bloqueiam o merge; há recomendação escrita
   para cada um, **nenhuma executada**.
2. Responder as **4 perguntas que só o aparelho responde** (seção "Esperando o operador").
3. Só então decidir o merge da PR #20, com o cuidado descrito no item 5 daquela seção.

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

| lead | correção | teste |
|---|---|---|
| 1 (BLOCKER) | a regra de "isto parece mídia?" saiu do `MediaVault` para `MediaHints`, objeto puro sem Android — mesmo padrão de `VoiceGateDecision`. O indício de imagem deixou de ser "contém a palavra" e passou a ser **emoji de mídia (📷/📸/🖼/🏞) OU o texto ser só o rótulo** ("Foto", "2 fotos", "Photo"), com `matches` sobre o texto inteiro. "me manda a foto" não dispara mais o fallback. | `MediaHintsTest` — 9 testes, verdes |
| 3 (MAJOR) | destino de `captureImageUri` virou `img-uri-$aroundTs-<seq>$ext`, com `AtomicLong`. Duas notificações de mesmo `postTime` não escrevem mais no mesmo arquivo. Cópia parcial é apagada em caso de falha, em vez de esperar a retenção. | coberto por leitura; sem teste JVM (depende de `ContentResolver`) |
| 5 (parqueado) | **endurecido sem supor nada do aparelho**: `captureLatestImage` passou a varrer a raiz **e** as 2 subpastas mais recentes, como o caminho de voz já fazia. Correto tanto se as imagens ficam na raiz quanto se ficam em subpasta de semana. A pergunta continua aberta para o operador. | — |
| 6 (MAJOR) | `IMAGE_WINDOW_FORWARD_MS` 8 s → **12 s**, cobrindo o horizonte real do retry (10,5 s) com margem. Os últimos 2,5 s de retry deixaram de ser caminho morto. | — |
| 9 (MAJOR, fora dos leads) | `captureImage` passou a rodar em coroutine própria (`scope.launch`), e a captura de áudio deixou de esperar por ela. `runPurge()` continua depois das duas, via `imageJob?.join()`, para a varredura de órfãos não apagar um arquivo antes de o `setImagePath` registrá-lo. | — |
| 4 (LOW) | sem correção, por decisão: é a propriedade que o áudio já tinha antes desta branch. Só documentada no código. | — |

Build: `:app:testDebugUnitTest` verde (9 testes). Nenhum teste em aparelho — ver
o topo deste arquivo.

Ver `docs/coordination/commit-ledger.md` para o commit de cada uma.

## Concílio de entrega

Rodado depois de a revisão fechar não-BLOCKER, contra o artefato aprovado.
4 lentes distintas, nenhuma vendo a saída da outra: **concorrência/ciclo de vida**,
**conformidade com as issues**, **regressão**, **adversarial**.

### Rodada 1 — 21 achados levantados

Corrigidos nesta rodada:

| origem | achado | correção |
|---|---|---|
| conformidade | **BLOCKER** — `EXTRA_MESSAGES` traz o histórico da conversa; pegar "a última COM imagem" fazia uma mensagem de TEXTO herdar a foto anterior | passa a usar **só a última entrada** do bundle, mais guarda de `timestamp` contra o `postTime` (60 s) |
| adversarial | **MAJOR** — emoji não ancorado: "comprei uma câmera nova 📸" disparava a varredura | emoji só conta **no início** do texto (`IMAGE_EMOJI_PREFIX`) |
| adversarial | **MAJOR** — falso negativo em grupo: `"Ana: Foto"` não casava, e o ramo de rótulo existe justamente para as versões sem emoji | prefixo `"Remetente: "` é removido antes de testar o rótulo |
| conformidade | **MAJOR** — a varredura de subpastas (correção do lead 5) pegava `Sent/` e `Private/`: imagem que **você enviou** podia vencer o desempate | só subpastas de nome numérico (ano+semana) |
| concorrência + adversarial | **MAJOR** — arquivo ainda em download era copiado truncado, e o retry parava no primeiro sucesso | candidato precisa estar sem escrita há `FILE_SETTLE_MS` (1,5 s) |
| concorrência + regressão | **MAJOR** — `@Synchronized` num `object` dá **um monitor só**: a varredura de imagem bloqueava a captura de áudio, refazendo pelo lock o acoplamento que a correção do achado 9 desfez pela coroutine | locks separados (`voiceLock`, `imageLock`) |
| concorrência + conformidade | **MAJOR** — `sweepOrphanImages` apaga por "não está no banco", e o arquivo nasce em disco até 10,5 s antes da linha; um purge de outra notificação (ou da MainActivity/RetentionActivity) apagava a cópia em andamento | carência de 2 min antes de varrer órfão, imagem e áudio |
| adversarial | **MAJOR** — stream vazio virava arquivo de 0 byte que contava como sucesso, **travava** o fallback de diretório e sumia sem aviso na tela | `dest.length() == 0` → apaga e devolve `null` |
| conformidade | **MAJOR** — o caminho URI não deduplicava pelo arquivo-fonte, contra regra explícita da #19 | memo `uri → caminho`, reaproveita a cópia |
| conformidade | **MINOR** — `DetailActivity` decodifica sem subamostragem, e `imagePath` agora é o **original** do WhatsApp, não mais a miniatura: risco de OOM sem `largeHeap` | `inSampleSize` com teto de 2048 px |
| adversarial | **MINOR** — `IMAGE_LABEL` não casava `"IMÁGENES"` (o `IGNORE_CASE` do Kotlin não liga `UNICODE_CASE`) nem `"2\u00A0fotos"` (NBSP) | `(?iu)` e separador aceitando NBSP; marcas bidi removidas |
| concorrência | **MINOR** — filho de `launch` sem `CoroutineExceptionHandler` derruba o processo | handler no `scope` |
| adversarial | **MINOR** — 2 dos 9 testes não provavam o que o nome anunciava | renomeados/reduzidos, e o comentário passou a dizer qual é a regra de verdade |

Viraram teste: **5 testes novos** (emoji digitado, prefixo de remetente nos dois
sentidos, rótulo de outras mídias, variantes de escrita). Total: **14 testes**,
`:app:testDebugUnitTest` verde.

### Contagens

| | |
|---|---|
| achados levantados (rodada 1) | 21 |
| corrigidos na rodada 1 | 13 |
| viraram teste unitário | 5 testes novos (9 → 14) |
| perguntas abertas para o operador | ver "Esperando o operador" |

### Rodada 2 — verificação e classificação (2 lentes: classificação, olho novo)

Os **12** fechamentos da rodada 1 foram verificados um a um no código: **todos FECHADOS**,
nenhum vivo. Mas a rodada 2 levantou **10 achados distintos** (13 brutos, 3 vistos pelas
duas lentes de forma independente — o que aumenta a confiança neles, não diminui).

**Nenhum foi corrigido, por contrato.** O `README.md` da coordenação e o briefing desta
rodada são explícitos: achado vivo depois da rodada 2 → **para e vai ao operador; nunca uma
terceira rodada**. Corrigir agora produziria mudança não verificada, que é exatamente o que
o teto de rodadas existe para impedir. Estão todos abaixo, com a classificação pedida.

| # | achado | sev. | classificação |
|---|---|---|---|
| S1 | **`SENDER_PREFIX` reabre o buraco que o `MediaHints` existe para fechar.** O strip é incondicional: qualquer texto cujos primeiros ≤40 chars terminem em `": "` perde esse pedaço. `"Ana: fotos"` (a pessoa digitou a palavra) → `"fotos"` → `matches` → **true** → varre o diretório e anexa foto alheia a uma mensagem de TEXTO. Idem `"Olha isso: foto"`, `"Assunto: fotos"`. **As duas lentes acharam isto sozinhas.** | MAJOR | **introduzido por correção da rodada 1** (a correção do falso negativo em grupo desfez parte da correção do BLOCKER) |
| S2 | **`FILE_SETTLE_MS` contradiz o cronograma de retry.** Última varredura em t+10,5 s, `settled` = t+9,0 s: arquivo com mtime em (t+9 s, t+12 s] **nunca** é aceito — não há retry depois. A janela útil é 9 s, não os 12 s que a constante e seu comentário afirmam; **o caso que motivou subir a janela de 8→12 s continua recusado.** Pior: na 1ª varredura (t+0,3 s) o filtro exige mtime ≤ t−1,2 s, então ela **só pode retornar arquivo anterior à notificação** — a coincidência temporal proibida pela #17 entrando pela porta construída para evitá-la. **As duas lentes acharam isto sozinhas.** | MAJOR | **introduzido por correção da rodada 1** |
| S3 | **`DetailActivity.looksLikeMedia` ainda é a regra frouxa antiga** (`containsMatchIn` sobre `imagem\|foto\|photo\|…` nos 40 primeiros chars) — literalmente o padrão que o `MediaHints` foi criado para eliminar, vivo na mesma branch. Não dispara captura, mas afirma ao usuário "imagem não capturada" para toda mensagem que mencione "foto", e é a regra que um mantenedor futuro vai encontrar primeiro e replicar. **As duas lentes acharam isto sozinhas.** | MAJOR | **pré-existente recém-visto** |
| S4 | `captureImageUri` agora pega o mesmo `imageLock` que `captureLatestImage` segura durante `listFiles` + `copyTo` de vários MB. O comentário diz que a cópia do URI "é imediata"; ela deixou de ser. E o lock não protege nada: `uriCopies`, `consumed` e `destSeq` já são thread-safe. | MINOR | **introduzido por correção da rodada 1** |
| S5 | `uriCopies` cresce sem limite pela vida do processo, sem invalidação. Só memória (o reuso revalida com `length() > 0`). `consumed` tem o mesmo crescimento e é anterior à branch. | MINOR | **introduzido por correção da rodada 1** (o gêmeo `consumed` é pré-existente) |
| S6 | Dedup **segmentada**: o memo de URI não alimenta `consumed`. A #19 pede dedup pelo *arquivo-fonte*; o que existe é dedup por URI de um lado e por `absolutePath:mtime` do outro, sem ponte — a mesma imagem pode ser copiada duas vezes por caminhos diferentes. | MINOR | **antigo** (fechamento só parcial do achado 9 da rodada 1) |
| S7 | `lastOrNull()` só é a mensagem desta notificação se o bundle vier do mais antigo para o mais novo. É o que o AOSP faz em `MessagingStyle.addMessage`, mas é o único dos 12 fechamentos cuja prova depende de comportamento externo. Degrada com segurança (cai no fallback por rótulo). | MINOR | **introduzido por correção da rodada 1** — e **só o aparelho confirma** |
| S8 | Os três caminhos de captura têm três contratos diferentes para o mesmo trabalho (destino, dedup, validação da cópia). `savePicture` nomeia com `bmp.hashCode()`, que é hash de **identidade**, não de conteúdo — o nome mente sobre ser endereçado por conteúdo. O guard de 0 byte só existe no caminho do URI. | MINOR | **pré-existente recém-visto**, amplificado pela branch |
| S9 | `imageJob?.join()` é guarda morta com comentário que parece carregar peso: quem protege o sweep é `ORPHAN_GRACE_MS`, e **tem** que ser ele, porque `MainActivity` e `RetentionActivity` também chamam `purge` e não têm job para esperar. Risco: alguém reduzir a carência confiando no `join`. | MINOR | **introduzido por correção da rodada 1** |
| S10 | Quatro constantes independentes respondem "quão perto no tempo é a mesma mensagem?" (60 s / 20 s / 15 s / 12 s), nenhuma derivada de outra. O caminho do URI é o **mais permissivo em tempo** (60 s) e o **único sem a trava semântica** da #17 (basta `messageImage != null`, sem passar por `looksLikeImageMessage`). | MINOR | **antigo** / misto |

### Contagens do concílio (as duas rodadas)

| | |
|---|---|
| lentes | 6 (rodada 1: concorrência, conformidade, regressão, adversarial · rodada 2: classificação, olho novo) |
| achados levantados | **31** (21 na rodada 1 + 10 na rodada 2) |
| corrigidos | **13** (todos na rodada 1; verificados FECHADOS na rodada 2) |
| viraram teste unitário | **5 testes novos** (9 → 14, `:app:testDebugUnitTest` verde) |
| **sobreviventes (vivos depois da rodada 2)** | **10** → param aqui e vão ao operador |
| perguntas abertas | **4** (ver "Esperando o operador") |
| rodadas | **2 de 2. Teto atingido. Não haverá terceira.** |

## Por que esta frente parou

Porque o teto de rodadas do concílio foi atingido com achados vivos. A rodada 2 confirmou
que as 13 correções da rodada 1 fecharam o que se propunham, e mostrou que **duas delas
abriram problema novo** (S1 e S2) — as duas achadas de forma independente pelas duas lentes.
Corrigir S1 e S2 agora seria mudança sem verificação, porque verificá-la exigiria a terceira
rodada que o contrato proíbe. A decisão é do operador.

**A branch NÃO está pronta para merge.** S1 e S2 são regressões da restrição central da #17
("não anexar imagem antiga a uma mensagem de texto por coincidência temporal").

### Recomendação para a retomada (não executada)

1. **S1** — o caminho mais curto é **remover `stripSenderPrefix`** (4 linhas): volta à garantia
   forte da rodada 1, ao custo do falso negativo em grupo nas versões sem emoji. O caminho
   correto, mais caro, é o prefixo só ser removido quando houver evidência de que a
   notificação é de grupo (`EXTRA_IS_GROUP_CONVERSATION` / `MessagingStyle.isGroupConversation`),
   em vez de casar qualquer `": "`.
2. **S2** — as três grandezas (janela, settle, escada de retry) precisam ser **derivadas umas
   das outras em código**, não coincidir por comentário. Enquanto isso não acontece, o mínimo
   é `ceiling` = último retry − `FILE_SETTLE_MS`, e a primeira varredura não rodar antes de
   `FILE_SETTLE_MS`.
3. **S3** — `DetailActivity.looksLikeMedia` deve passar a chamar `MediaHints`.

## Esperando o operador

0. **Decidir sobre os 10 achados vivos da rodada 2**, em especial S1, S2 e S3. Nenhum foi
   corrigido — por contrato, não por falta de tempo.

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
3. **Ordem de `EXTRA_MESSAGES` (achado S7).** Pergunta literal: *nas notificações de
   MessagingStyle do WhatsApp deste aparelho, o bundle `EXTRA_MESSAGES` vem do mais antigo
   para o mais novo?* O fechamento do BLOCKER da rodada 2 assume que sim (é o que o AOSP
   faz). Se alguma versão inverter, o caminho do URI vira falso negativo silencioso.

4. **Conflito semântico no merge com `development` (lente de regressão, MAJOR).**
   `development` ganhou `isDuplicateRepost(pkg, title, text)` — early-return de 2 s — que roda
   **antes** da extração de `EXTRA_PICTURE`. O WhatsApp costuma postar o rótulo primeiro e só
   depois atualizar a notificação com o bitmap/URI, **com texto idêntico**: depois do merge,
   essa segunda notificação é descartada e o caminho de imagem nunca roda para ela.
   `git merge-tree` **não** acusa isso — os hunks estão em regiões diferentes e mesclam limpo.
   As duas features nunca foram exercitadas juntas. Isto contraria uma regra já estabelecida
   em `development`, então sai do concílio e vem para o operador.

5. **Merge da PR #20.** Decisão do operador; nenhum agente mescla. Nota mecânica: a branch
   está atrasada em relação a `development` (merge-base `fe713e0`). `git merge-tree` acusa 5
   arquivos; o de `NotifListenerService.kt` é um bloco de import onde **os dois lados são
   necessários** (`android.os.Build` de `development`, `android.net.Uri` da branch) —
   resolver com `--ours`/`--theirs` **não compila**. Nos 3 arquivos de docs o lado da branch
   é o mais novo.
