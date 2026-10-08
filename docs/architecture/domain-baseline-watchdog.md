# WA-Keeper: baseline de domínios e laboratório Goals Kit Watchdog

Status: **DRAFT / documentação, não aprovação arquitetural**. Origem: branch `development` consultada em 2026-10-08. Escopo desta etapa: **nenhuma mudança em código, dependências, banco, builds ou CI**.

## Objetivo

Utilizar o WA-Keeper como bancada para testar princípios de desenho de domínios derivados do YB Convênio, e depois propor lições comprovadas ao Governance Kit. O componente experimental chama-se **Goals Kit Watchdog**; **não está implementado**. Nenhuma regra desta proposta é automaticamente aplicada ao YB Convênio.

## Princípios transferíveis do YB Convênio

1. **Fronteiras de domínio explícitas:** responsabilidades e invariantes descritas; dependências para dentro do domínio, não para a UI.
2. **Identidade ≠ papel ≠ identificação externa:** o conceito é transferível; **não** copiar `Person/Patient`, SUUID ou regras clínicas.
3. **Adapters para integrações externas:** serviços Android, WhatsApp/Business, Drive e motores de transcrição são fronteiras de infraestrutura, não donos de regras de negócio.
4. **Contratos estáveis entre módulos:** preservar compatibilidade de dados e comportamento; documentar consumidores afetados antes de alterar.
5. **Rastreabilidade sem dados privados:** correlacionar pedido, alteração, teste, resultado e evidências de forma segura.
6. **Simplicidade operacional:** manter um app Android modular, sem impor microserviços, protobuf, Go ou arquitetura distribuída.
7. **Segurança por padrão:** segredos fora do repositório e do histórico; auditoria de vazamento no commit, push e CI.
8. **Decisões abertas são abertas:** não transformar hipóteses desta análise em decisões definitivas.

## Mapa observado (primeira leitura)

Os agrupamentos abaixo derivam de arquivos e referências reais. São **domínios candidatos**, não módulos físicos existentes; a maioria das classes reside no mesmo pacote `br.com.wanotifkeeper`.

| Fronteira proposta | Evidência observada | Dono provável do comportamento |
| --- | --- | --- |
| Ingestão de notificações | `NotifListenerService.kt`, `NoiseFilter.kt`, `RepostGuard.kt` | interpretar/capturar evento, deduplicar e persistir |
| Identidade e conversas | `ConversationIdentity.kt`, `ContactDirectory.kt`, `ConversationActivity.kt` | identidade canônica de conversa, contato e vínculo |
| Persistência e memória | `NotifDatabase.kt`, `MemoryRepository.kt`, `EntityAssociationsActivity.kt` | mensagens, associações, entidades e histórico |
| Mídia e retenção | `MediaVault.kt`, `RetentionPolicy.kt`, `AudioPlayer.kt` | arquivos capturados, reprodução, retenção |
| Transcrição e voz | `TranscriptionClient.kt`, `VoiceCommandEngine.kt`, `VoiceGateDecision.kt` | transcrição offline, comandos de voz, métricas |
| Agendamento e envio | `ScheduledMessageCoordinator.kt`, `ScheduledMessageStore.kt`, `ScheduledMessageAlarm.kt`, `ReplySender.kt`, `AutomatedSendGate.kt` | fila, destinatário, horário, tentativa, envio, falha e recuperação |
| Backup e restauração | `DriveBackupStore.kt`, `DriveBackupPolicy.kt`, `scripts/android-data-backup.sh`, `tools/local-backup.py` | cópia, integridade e recuperação |
| UI e configuração | `MainActivity.kt`, `SettingsActivity.kt`, telas `*Activity.kt`, `Prefs.kt` | apresentação e preferências, não regras de identidade |

### Relações diretamente observadas

- `NotifListenerService` invoca `ConversationIdentity`, `MediaVault`, `NotifDatabase` e `DriveBackupStore`. Isso torna a captura uma **área de alto raio de impacto**: identidade, mídia, banco e backup se encontram no serviço.
- `DriveBackupStore` consulta `NotifDatabase` diretamente. É necessária análise de consistência do snapshot e do contrato de restauração.
- `AudioTranscriptionManager`, em `TranscriptionClient.kt`, consulta banco e histórico de transcrições diretamente; verificar se processamento, persistência e UI estão corretamente separados.
- `RoomScheduledMessageStore` implementa `ScheduledMessageStore`: **bom precedente de porta/adapter**, a ser avaliado para outros limites de infraestrutura.
- `ConversationIdentity` possui `canonicalSender`, `stableKey`, `groupKey` e agrupamento de conversas: um ponto central de regra que deve possuir testes de compatibilidade.

### Riscos a verificar, sem assumir defeito

- **Acoplamento transversal:** serviço de notificação conhece persistência, identidade, mídia e backup. Medir responsabilidades e dependências antes de qualquer sugestão de extração.
- **Grande centro de persistência:** `NotifDatabase.kt` reúne registros de notificação, transcrição, memória, vínculos e configurações. Identificar entidades compartilhadas e proprietários dos dados; não dividir banco prematuramente.
- **Identidade inconsistente entre UI, agendamento e WhatsApp:** conferir a passagem de chave canônica e desambiguação da escolha do destinatário até o disparo. Não afirmar correção antes de testes.
- **Regressões de interface e áudio:** confrontar fluxos de controles de reprodução e transcrição com `docs/validated-features.md`; a existência de testes unitários não prova a presença dos controles na tela.
- **Backup e segurança:** separar backup de dados Android, backup de código Git e backup criptografado de credenciais. Nunca subir dados pessoais como evidências.

## Contratos existentes que continuam prevalecendo

`AGENTS.md` e `docs/validated-features.md` preservam funcionalidades PROTECTED (transcrição offline, reprodução de áudio, envio agendado, backup/restauração). Esta proposta **não as altera**. Sempre que houver divergência, suspender mudanças e solicitar decisão do operador.

## Primeira matriz de impacto proposta

| Alteração em | Exigir análise de impacto sobre |
| --- | --- |
| `ConversationIdentity` / `ContactDirectory` | agrupamento, importação, destinatário agendado e associação de entidade |
| `NotifDatabase` e migrações | captura, históricos, associações, agendamentos, backup/restauração |
| `NotifListenerService` | captura, arquivos, identidade, notificações, backup |
| `MediaVault` / `AudioPlayer` | reprodução, transcrição, retenção e compartilhamento |
| `TranscriptionClient` | modelo ativo, disponibilidade offline, histórico e avaliação |
| `ScheduledMessage*` | fila, ordem, falha, edição/eliminação/reenvio, identidade do destino |
| `DriveBackup*` | integridade, restauração, seleção do destino e preservação de dados |
| tema, layouts, Activity base | controles PROTECTED efetivamente renderizados e acionáveis |

A matriz é **proposta para validação**, não uma declaração de que já existem testes automatizados para todas as células.

## Goals Kit Watchdog: desenho inicial, NÃO IMPLEMENTADO

**Entradas:** especificação da tarefa, diff e commits, matriz de impacto, contratos protegidos, testes e evidências.

**Verificações determinísticas:** arquivos alterados, testes requeridos, migrações, padrões de segredo, correspondência entre requisito e evidência, estado de revisão, integridade de refs e backups.

**Análise LLM (advisory):** hipóteses de impacto semântico, violação de fronteiras, lacunas entre pedido e resultado. A LLM não pode certificar execução de teste que não aconteceu.

**Saída:** relatório legível e máquina-legível com `PASS`, `FAIL`, `UNKNOWN` e `NOT_APPLICABLE`, evidências e justificativas. `UNKNOWN` nunca equivale a `PASS`. Futuramente, CI bloqueia violações objetivas; avisos semânticos exigem revisão humana.

**Portabilidade:** núcleo agnóstico ao projeto; adaptadores Android/Gradle/GitHub ficam locais. Migração ao Governance Kit apenas após aprendizagem e aprovação.

## Segurança e continuidade histórica

- Credenciais fora do Git; `.gitignore` não protege conteúdo já rastreado. Validar `pre-commit`, `pre-push` e CI por varredura de segredos; não imprimir segredos nos relatórios.
- Um segredo exposto deve ser revogado/rotacionado. Reescrita de histórico exige plano de recuperação verificado, cópias seguras, autorização expressa e avaliação de todos os clones/refs.
- `publisher/scripts/purge-sensitive-history.sh` usa `git push --force --mirror`; classificar como operação destrutiva de alto risco. Não executá-lo automaticamente.
- Diário local de engenharia com requisito, decisão, branch, commits, evidências, testes e aprovação. Não duplicar Git: referenciar SHAs.
- Espelho Git durável e backup independente, preferencialmente fora do mesmo disco. Testar restauração. Backup de credenciais separado e criptografado.
- Não persistir em repositório ou relatório conversas, áudios, tokens e chaves privadas.

## Próximas verificações documentais (sem código)

1. Inventariar a cadeia concreta de execução e dependências de cada domínio a partir do código e dos testes.
2. Identificar proprietários de dados, contratos públicos e caminhos críticos entre domínios.
3. Confrontar matriz de impacto com testes existentes; classificar ausência de evidência como `UNKNOWN`, não `FAIL` automático.
4. Priorizar uma regressão conhecida para ensaiar o primeiro gate do Watchdog, sem implementação até autorização.
5. Propor promoção ao AI-Agents apenas de regras universais comprovadas no laboratório.
