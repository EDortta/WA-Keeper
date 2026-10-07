# Processo de release

## 1. Princípio central

Há uma diferença entre versão em desenvolvimento, candidata à loja e versão publicada.

- qualquer branch pode gerar APK de laboratório;
- somente `play` pode gerar uma candidata de loja;
- somente código já aprovado pela Google Play pode chegar a `main`;
- a promoção para `main` não recompila o aplicativo: promove exatamente o commit e o artefato que já foram aprovados.

## 2. Desenvolvimento

O trabalho nasce em branches de feature, correção, experimento ou integração.

Qualquer branch pode compilar:

    bash scripts/build.sh --lab

Isso permite testar uma combinação de mudanças sem transformar a branch em candidata de loja.

`development` é a linha de integração do trabalho em andamento.

Ela pode receber múltiplas features simultaneamente e continua sendo LAB.

## 3. Preparação da próxima versão

Quando houver uma composição de features considerada pronta para distribuição:

1. escolher a próxima `versionName` e o próximo `versionCode`;
2. preparar `play` com exatamente o código e as features candidatas;
3. revisar `publisher/features.properties`;
4. gerar um snapshot imutável da configuração da versão;
5. executar os checks de publicação;
6. gerar o AAB com `scripts/build.sh --store`;
7. registrar commit e hash do AAB.

A partir desse ponto, a candidata fica congelada.

## 4. Estados da versão

Uma versão pode assumir os estados:

- WORKING: versão ainda em preparação;
- CANDIDATE: composição fechada em `play`;
- SUBMITTED: enviada à Google Play;
- APPROVED: aprovada pela loja;
- PUBLISHED: promovida para `main` e tratada como produção oficial.

Enquanto estiver WORKING, a configuração da versão corrente pode ser alterada.

Depois de CANDIDATE, o snapshot daquela versão é somente leitura.

## 5. Enquanto a Play analisa

Quando uma versão estiver em análise:

- `play` permanece congelada naquele commit;
- `development` continua recebendo novas mudanças;
- branches de feature continuam normalmente;
- novas APKs de laboratório podem ser geradas de qualquer branch;
- nenhuma mudança nova entra na candidata já submetida.

Se for necessário alterar código após a submissão, isso cria uma nova candidata e normalmente exige novo `versionCode`.

## 6. Publicação

Depois que a Google Play aprovar a candidata:

- promover exatamente o commit aprovado de `play` para `main`;
- não recompilar;
- não alterar feature flags;
- criar a tag semântica da versão publicada;
- registrar `versionName`, `versionCode`, commit e hash do AAB aprovado;
- marcar o snapshot como PUBLISHED.

Fluxo conceitual:

    branches -> development -> play -> Google Play -> main

## 7. Snapshots de versão

Cada versão candidata deve ter um snapshot em:

    publisher/versions/<versionName>.properties

Esse snapshot registra, no mínimo:

- versionName;
- versionCode;
- status;
- commit;
- hash do AAB quando existir;
- estado LAB e LOJA de cada feature.

Snapshots de versões anteriores são somente leitura.

O `features.sh` deve evoluir para exibir a versão atual e permitir consulta de versões antigas, sem permitir alterações nelas.

## 8. Hotfix

Hotfix publicado segue:

    hotfix/* -> play -> Google Play -> main

Depois deve ser retroportado para `development` quando aplicável.
