# Estratégia de branches

## Princípio

Branch não define uma feature.

Uma branch pode conter uma ou várias features, e uma feature pode atravessar mais de uma branch ao longo da integração.

Por isso, branch e feature são conceitos independentes.

## development

Linha de integração do laboratório.

Recebe:

- recursos novos;
- experimentos;
- correções em validação;
- capacidades que podem nunca ser aceitas pela Google Play;
- integrações entre features.

Não representa produção.

Builds geradas aqui são LAB.

## feature/*, fix/* e outras branches de trabalho

Podem existir livremente para desenvolvimento isolado.

Qualquer uma delas pode gerar APK para teste usando:

    bash scripts/build.sh --lab

Nenhuma branch de trabalho produz build de loja.

## play

Branch da candidata à Google Play.

Quando uma versão é fechada:

- `play` recebe exatamente a composição candidata;
- a configuração de features é congelada;
- a versão recebe `versionName` e `versionCode`;
- gera-se o AAB da loja;
- o commit submetido fica congelado durante a análise.

Enquanto isso, o trabalho continua normalmente em `development` e nas demais branches.

## main

Produção oficial.

`main` corresponde somente a código efetivamente aprovado pela Google Play.

Regras:

- não recebe commits diretos;
- recebe exatamente o commit aprovado da candidata;
- não se recompila uma versão ao promovê-la para `main`;
- cada publicação recebe uma tag semântica, por exemplo `v1.1.0`.

## Fluxo

    feature/*, fix/*, outras branches
                |
                v
           development
                |
                v
              play
                |
                v
          Google Play
                |
                v
              main

## Regras de build por branch

- qualquer branch: LAB permitido;
- development: LAB;
- play: LAB e STORE, conforme o objetivo;
- main: não é branch de experimentação; representa publicação já aprovada.

## Regra anti-regressão de publicação

Nunca fazer merge automático de `development` para `play`.

Nunca alterar uma candidata já SUBMITTED.

Nunca promover para `main` uma build diferente daquela que a Google Play aprovou.
