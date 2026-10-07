# Build feature gating — 2026-10-07

Status: IMPLEMENTED (repository-level). BUILD-GREEN ainda precisa ser produzido no devel3.

Implementado:
- compilador único `scripts/build.sh`;
- macro global `BuildConfig.WA_STORE_BUILD`;
- macros `BuildConfig.WA_FEATURE_*` geradas do registro;
- fonte de verdade `publisher/features.properties`;
- política deny-by-default para novas features (`storeAllowed=false`);
- validação dupla: shell + Gradle;
- `publisher/scripts/build-play.sh` agora usa o compilador único;
- documentação em `publisher/FEATURE-FLAGS.md`.

Regra testável:
`--store` + feature com `storeAllowed=false` deve abortar antes da compilação e também deve ser rejeitado pelo Gradle se chamado diretamente.

Pendência deliberada:
As features existentes ainda precisam ser ligadas aos macros em código/UI/manifest/dependências. Portanto, este commit estabelece a infraestrutura e a trava de configuração, não declara ainda isolamento binário completo de todas as features.
