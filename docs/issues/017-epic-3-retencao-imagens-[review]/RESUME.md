# RESUME — EPIC 3: Retenção de imagens recebidas no WhatsApp

- work_id: epic3-image-review
- issues: #17 (épica), #19 (`IMG-001`), PR #20
- date: 2026-09-01
- status: `[review]` — código escrito e empurrado; **nenhuma revisão, nenhum teste,
  nenhum concílio ainda**. PR #20 repontada de `main` para `development` em 2026-09-01.

## Next Step (DO THIS FIRST)

Revisar os 3 commits da branch contra os critérios de aceite da #19 e da #17, no
worktree `../WA-Keeper--epic3-image-review`. Só depois de a revisão retornar
não-BLOCKER é que roda o concílio de entrega (2 rodadas, teto). Merge é decisão
do operador.

## O que as issues pedem

`EXTRA_PICTURE` não é confiável para toda notificação de imagem, então a linha da
mensagem persiste com `imagePath` nulo e a imagem some quando o WhatsApp a apaga.
A #19 manda adicionar captura best-effort no `MediaVault` a partir do diretório
oficial de mídia, com `EXTRA_PICTURE` continuando como fonte preferencial,
dedup pelo arquivo-fonte, retry curto com backoff, sem mexer no schema
(`imagePath` já existe) e **sem regressão no áudio**.

## Entregue (branch `feature/epic-3-image-retention`, 3 commits, +133/−14)

| commit | o quê |
|---|---|
| `8abca07` | captura de imagem via diretório de mídia (`MediaVault.captureLatestImage`) |
| `bce7983` | anexar caminho de imagem tardiamente (`NotifDao.setImagePath`) |
| `fa4b660` | persistir imagem além do bitmap da notificação (URI de `MessagingStyle`) |

Três caminhos em cascata no `NotifListenerService`: `EXTRA_PICTURE` →
URI de `MessagingStyle` (`captureImageUri`) → varredura do diretório de mídia
com retry `300/1200/3000/6000 ms` (`captureLatestImage`).

## Concílio

- Rodadas: **0**. Achados levantados: —. Sobreviventes: —. Viraram teste: —.
  Perguntas abertas: —.
- Pendente: concílio de entrega, antes do merge.

## Riscos conhecidos (não confirmados — entram como leads da revisão)

Ver `docs/coordination/briefing-agente-B.md`.
