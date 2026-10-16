# ENT-001 — Inventário e limites do domínio Entidades

Issue de referência: #64. Super-issue: #62.
Branch: `feature/entities-typed-api-contract`.
Estado: **INVENTÁRIO**, sem modificações de comportamento.

## Objetivo

Fazer de **Entidade** um agrupamento lógico escolhido pelo usuário, distinto de contato do aparelho, conversa WhatsApp/Business e número de telefone. Introduzir contrato interno de domínio sem reescrever dados existentes.

## Implementação encontrada em development (levantamento inicial)

- `EntitiesActivity.kt`: lista entidades e cria entidades chamando `MemoryRepository.createEntity`.
- `EntityDetailActivity.kt`: visualiza vínculos, renomeia, inicia mesclagem e exclusão, aciona backup e preferências de transcrição.
- `EntityAssociationsActivity.kt`: reúne candidatas de conversas via `ConversationIdentity.conversationBuckets` e contatos locais via `ContactsContract`; deduplica contatos por número normalizado, seleciona associações e persiste links.
- `MemoryRepository.kt`: concentra operações `createEntity`, `linkConversation`, `ensureEntityForConversation`, `resolveConversation`, `renameEntity`, `deleteEntity` e `mergeEntities`, além de rotinas de memória.
- `NotifDatabase.kt`: define `MemoryEntity`, `EntityLinkEntity` e os DAOs; a camada de apresentação também acessa `db.memory()` diretamente.

## Riscos e fronteiras

1. `MemoryRepository.deleteEntity` remove vínculos e entidade; `mergeEntities` transfere vínculos e remove a origem. **Não mudar nem executar essas operações durante o inventário.** Avaliar transação, preservação de dados e desfazer antes de tocar nesses fluxos.
2. O vínculo atualmente utiliza `packageName`, `sender` e `role`; não assumir que nome visível ou telefone representa identidade estável.
3. A tela de associações produz chaves derivadas e deduplica contatos; esse trabalho é limítrofe entre **Entidades**, **Conversas** e **Identidade**. Não migrar resolução de destinatários da ID-001 nesta etapa.
4. `MemoryRepository` cruza responsabilidades de Entidades e Memória. Não mover o armazenamento de memória (MEM-001) nesta feature.
5. Evitar alterações destrutivas de Room, DAOs, backups, filas, transcrição ou Watchdog.

## Plano de implementação

- [ ] Completar levantamento das referências a `db.memory()`, `MemoryRepository` e entidades/vínculos em todo o código.
- [ ] Documentar semântica atual de criar, associar, desassociar, mesclar e excluir, incluindo comportamento e limites de desfazer.
- [ ] Definir interface interna mínima do domínio para operações já existentes, preservando assinaturas e comportamento legado.
- [ ] Fazer `MemoryRepository` implementar o contrato sem reescrever algoritmos.
- [ ] Migrar **um consumidor por vez**, começando por leitura/criação; não começar por mesclagem/exclusão.
- [ ] Adicionar testes de vínculo, homônimos, contatos duplicados, erros e integridade; recolher evidências.
- [ ] Builds debug e release, testes protegidos, smoke test no Android e revisão de diff antes do PR.

## Aceitação

Não misturar conversas nem contatos de pessoas distintas, não perder associação ou dados históricos, não degradar operações existentes, preservar ou explicitar corretamente a capacidade de desfazer, registrar evidências seguras. Sem aprovação expressa, não modificar comportamento de ações destrutivas.

**Nota:** este documento é inventário preliminar, não certificação de que todos os consumidores foram encontrados.

## Primeiro incremento: catálogo de entidades (2026-10-16)

Introduzido `EntityCatalog` com `entitiesFlow()` e `createEntity(name, kind)`; `MemoryRepository` implementa o contrato delegando ao mesmo `MemoryDao` utilizado antes. `EntitiesActivity` migrou somente listagem e criação para o contrato, sem alterações de UI ou persistência.

Nenhuma modificação em vínculos, associação, mesclagem, exclusão, banco, backup ou Watchdog. O primeiro consumidor não acessa mais `db.memory()` para listagem.

**Estado: IMPLEMENTED**, ainda não BUILD-GREEN nem DEVICE-VALIDATED. É necessária compilação via `scripts/build.sh`, testes existentes e smoke test no Android (listar, criar e reabrir entidade, sem efetuar exclusão ou merge).

## Validação do primeiro incremento e segundo incremento (2026-10-16)

O usuário informou `BUILD SUCCESSFUL` para o release, com evidência publicada em `WA-Keeper/general/feature-entities-typed-api-contract/2026-10-16/153200-9f6632b2/manifest.json`, e confirmou em seguida que os testes no aparelho deram certo. Primeiro incremento: **BUILD-GREEN / DEVICE-VALIDATED** (relato do usuário).

Segundo incremento: `EntityCatalog` também expõe `entityById` e `linksForEntity`. A implementação delega às consultas atuais do `MemoryDao`; `EntityDetailActivity` e `EntityAssociationsActivity` utilizam o contrato para essas leituras (e listagem na seleção de mesclagem). Os caminhos de escrita, `saveAndFinish`, renomear, unir, excluir e operações do DAO não foram alterados.

**Estado do segundo incremento: IMPLEMENTED, aguardando BUILD-GREEN e DEVICE-VALIDATED.** Verificar título, links existentes, seleção de vínculos e abertura das telas, sem persistir alterações nem realizar mesclagem ou exclusão nesta rodada.

## Correção de busca com diacríticos (2026-10-16)

Relato do usuário: nomes de contatos com acentos não eram encontrados quando a consulta era digitada sem acentos. Identificado filtro da `EntityAssociationsActivity` baseado somente em `lowercase().contains()`. Criada `EntitySearch` com normalização Unicode NFD, remoção das marcas diacríticas e comparação sem distinguir caixa; aplicado apenas ao filtro visual de títulos e subtítulos. Os nomes, identificadores, números e links persistidos não são alterados. Testes de `João/joao`, `José/Jose`, `Márcia/marcia`, pesquisa por subtítulo e não correspondência adicionados. **Pendente de build e teste no dispositivo.**
