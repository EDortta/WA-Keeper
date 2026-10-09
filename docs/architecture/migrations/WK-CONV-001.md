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
