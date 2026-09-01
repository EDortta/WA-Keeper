# Briefing — Agente B · EPIC 3 (#17 / #19 / PR #20)

**Worktree:** `/home/esteban/Sync/Projects/WA-Keeper--epic3-image-review`
**Branch:** `feature/epic-3-image-retention` (3 commits à frente de `development`)
**Entregável:** PR #20 revisada, corrigida e pronta para o operador decidir o merge.

## Escopo

1. Revisar os 3 commits contra os critérios de aceite da #19 e a Definition of
   Done da #17. A revisão vale como a crítica por commit que faltou — os três
   commits chegaram sem crítica registrada.
2. Corrigir o que a revisão apontar, com teste onde couber (o repo já tem testes
   unitários: `VoiceCommandParserTest.kt`, `VoiceGateDecisionTest.kt`).
3. Só depois de a revisão retornar não-BLOCKER: **concílio de entrega**, 2 rodadas
   no máximo, lentes distintas, achados nunca decisões. Registrar contagens no
   `RESUME.md` da issue 017 e no `docs/napkin-lessons.md`.
4. **Não mesclar.** Merge para `development` e qualquer deploy são do operador.

## Leads da revisão — hipóteses, não veredictos

Levantados na leitura do diff; confirme ou descarte cada um com evidência.

1. **Falso positivo de imagem em mensagem de texto.** `isImage` inclui
   `MediaVault.looksLikeImageMessage(text)`, cujo regex casa `foto|imagem|image|
   photo|picture|fotografia` em qualquer lugar do texto. Uma mensagem de texto
   dizendo "me manda a foto" dispara o fallback de diretório e pode anexar uma
   imagem alheia dentro da janela temporal. A #17 proíbe isso explicitamente:
   *"Não anexar imagem antiga a uma mensagem de texto por coincidência temporal."*
2. **Possível regressão no áudio.** `key(f)` mudou de `name:lastModified` para
   `absolutePath:lastModified`, e o set `consumed` é **compartilhado** com a
   captura de voice notes. A #17 e a #19 exigem "sem regressão na captura de
   áudio". Verificar o efeito real na dedup de áudio.
3. **`captureImageUri` não deduplica e pode colidir.** Não adiciona nada a
   `consumed`, e nomeia o destino `img-uri-$aroundTs$ext`: duas notificações com
   o mesmo `postTime` escrevem no mesmo arquivo.
4. **Dedup só vive em memória.** `consumed` é um set em processo; se o listener
   for morto e recriado, a mesma imagem pode ser copiada de novo.
5. **O caminho do diretório pode estar errado.** `captureLatestImage` faz
   `listFiles` só na raiz de `WhatsApp Images`, enquanto o caminho de voz percorre
   subpasta `<ano+semana>`. Se o WhatsApp desta versão guardar imagens em subpasta,
   o fallback nunca acha nada. **Verificar no aparelho** antes de confiar no código.
6. **Janela x retry inconsistentes.** O retry soma ~10,5 s
   (`300/1200/3000/6000`), mas `IMAGE_WINDOW_FORWARD_MS` é 8 s: um arquivo que
   aterrisse em t+9 s é buscado pelo retry e recusado pela janela.
7. **Limpeza de órfãos.** Confirmar que `Retention.purge` cobre os arquivos
   `img-*` e `img-uri-*` — é item da DoD da #17.
8. **Degradação sem `MANAGE_EXTERNAL_STORAGE`.** A DoD exige degradar para o
   comportamento atual sem crash. `hasAllFilesAccess()` cobre `captureLatestImage`,
   mas confirmar o caminho do URI de `MessagingStyle`.

## Restrições

- Aparelho de teste é **um só** e é o telefone pessoal do operador. Anuncie o uso
  no `docs/coordination/commit-ledger.md` antes de conectar. Se aparecer diálogo
  do sistema não relacionado (alarme, ligação, lembrete de medicação): **pare de
  tocar na tela** e espere. Nunca toque coordenada às cegas.
- Máximo 2 builds Gradle simultâneos na máquina; serialize pelo ledger.
- Commits só nesta branch, neste worktree. Nada em `main`.
