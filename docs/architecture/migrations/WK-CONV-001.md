# Migração de domínios — alteração WK-CONV-001

Estado: IMPLEMENTED / ainda não BUILD-GREEN nem DEVICE-VALIDATED.
Origem: `development`. Branch isolada: `feature/conversations-typed-api-contract`.
Feature afetada: representação/identificação de conversas. Domínio principal: Conversas.
Escopo de escrita autorizado: Conversas. Outros domínios: leitura somente.

## Motivação
Introduzir contrato explícito e tipado entre consumidores e mecanismo de normalização de nomes/chaves de agrupamento, aplicando a baseline derivada do YB Convênio.

## Modificações
- `ConversationNaming.kt`: interface interna com `canonicalSender` e `groupKey`.
- `ConversationIdentity.kt`: implementa o contrato, sem modificar corpos dos métodos.

## Fora do escopo
Nenhuma mudança em Contatos, Entidades, Memória, Room, DAOs, migrations, agendamento, transcrição, voz ou Watchdog. Nenhuma substituição dos identificadores legados. O contrato atual conserva strings para compatibilidade; tipos semânticos dedicados serão considerados em alterações específicas futuras.

## Avaliação de compatibilidade
Não há alteração intencional no comportamento nem nos dados persistidos. A inferência é baseada em inspeção do diff, **não em execução de testes**. Mudanças de chamadas e migrações de consumidores dependem de revisão dos contratos de seus domínios e autorização correspondente.

## Gates pendentes
- Executar `scripts/build.sh` nos perfis exigidos por `AGENTS.md`.
- Rodar testes de normalização e homônimos, inclusive caminhos legados.
- Verificar smoke tests dos fluxos protegidos afetados.
- Inspecionar/registrar resultado e evidências antes de integrar.

## Histórico e rollback
Commits: `b373a427`, `6cbce99d`.
Ao integrar, registrar SHA final; caso contrário, preservar o registro como não integrado. Reverter por commit rastreável, não descartar histórico da branch sem validação.

## Retomada após alinhamento com development (2026-10-16)

Levantamento preliminar do código da branch:

- `ConversationNaming.kt` define dois métodos (`canonicalSender` e `groupKey`) com parâmetros legados `String`.
- `ConversationIdentity.kt` implementa o contrato e conserva também `stableKey`, `sameConversation`, `conversationBuckets` e regras de visibilidade.
- `MainActivity.kt` consome diretamente `ConversationIdentity.conversationBuckets` e `canonicalSender`.
- `ConversationActivity.kt` consome diretamente `ConversationIdentity.sameConversation`.
- `ConversationImportActivity.kt` não contém referências textuais a essas chamadas na inspeção inicial.

Este é um inventário **parcial**, não uma afirmação sobre todos os consumidores do repositório. Antes de ampliar contratos ou migrar outros domínios, executar varredura integral, conferir o contrato de cada consumidor e estabelecer testes de compatibilidade.

### Próxima etapa proposta (não implementada nesta atualização)

1. Completar inventário de referências e chamadas duplicadas de normalização/agrupamento.
2. Separar API de domínio da implementação, sem mudar persistência nem IDs existentes.
3. Migrar um consumidor por vez, com testes específicos de homônimos, grupos, chaves estáveis, fallback legado e importações.
4. Registrar build debug/release, smoke test e evidências antes de declarar a migração validada.

A incorporação da `development` ocorreu pelo merge `25ee765`. Os testes Python das infraestruturas de evidências e assinatura foram executados com sucesso, conforme informado pelo usuário; esses resultados **não comprovam** ainda os gates Android da CONV-001.

## Incremento de contrato (2026-10-16)

O contrato `ConversationNaming` agora também expõe `conversationBuckets` e `sameConversation`. `ConversationIdentity` continua sendo a implementação existente; apenas declara `override` nesses métodos. A alteração não modifica algoritmos, dados ou consumidores.

Adicionado teste `groupingAndMatchingAvailableViaDomainContract` para assegurar que agrupamento e distinção entre conversas homônimas com chaves diferentes funcionam quando o acesso ocorre pela interface.

Estado: IMPLEMENTED. Teste Android/JVM e build pendentes de execução em devel3. Não houve migração de `MainActivity` ou `ConversationActivity`, pois pertencem aos consumidores e exigem validação própria.
