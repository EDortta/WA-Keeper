# WA Keeper — Google Play Publisher

Esta pasta é a fonte de verdade para publicação do WA Keeper na Google Play.

## Objetivo

Publicar uma variante compatível com as políticas da Google Play sem comprometer a versão sideload existente.

Tudo relacionado à publicação deve ficar aqui: processo, critérios, riscos, textos da loja, telas, políticas, evidências e scripts.

## Estrutura

- `PROCESS.md`: processo completo e critérios de passagem.
- `TARGET.md`: alvo técnico/comercial da variante Play.
- `RISKS.md`: permissões e funcionalidades sob revisão.
- `STORE-LISTING.md`: textos e narrativa da ficha da loja.
- `SCREENSHOTS.md`: roteiro e requisitos das telas.
- `PRIVACY.md`: matriz de dados e declarações a validar.
- `scripts/check-play-readiness.sh`: auditoria automática antes de publicar.
- `screenshots/`: originais e finais usados na Play Console.
- `assets/`: ícone, feature graphic e demais peças.
- `evidence/`: saídas de auditoria, builds e evidências de revisão.

## Regra principal

Nenhuma declaração pública é considerada verdadeira por intenção. Ela deve ser compatível com o binário efetivamente enviado.

A versão sideload e a versão Play podem divergir em permissões e funcionalidades quando necessário.
