# WA-Keeper: baseline de domínios e transferência de princípios do YB Convênio

Status: **DRAFT / documentação, não aprovação arquitetural**. Origem: branch `development` consultada em 2026-10-08. Escopo desta etapa: **nenhuma mudança em código, dependências, banco, builds ou CI**.

## Objetivo

Utilizar o WA-Keeper como bancada para adaptar e validar os princípios arquiteturais do YB Convênio. Este documento **não projeta nem implementa o Goals Kit Watchdog**: esse componente tem trabalho e conversa próprios. Nenhuma regra desta proposta é automaticamente aplicada ao YB Convênio.

## Princípios transferíveis do YB Convênio

1. **Fronteiras de domínio explícitas:** responsabilidades e invariantes descritas; dependências para dentro do domínio, não para a UI.
2. **Identidade ≠ papel ≠ identificação externa:** o conceito é transferível; **não** copiar `Person/Patient`, SUUID ou regras clínicas.
3. **Adapters para integrações externas:** serviços Android, WhatsApp/Business, Drive e motores de transcrição são fronteiras de infraestrutura, não donos de regras de negócio.
4. **Contratos estáveis entre módulos:** preservar compatibilidade de dados e comportamento; documentar consumidores afetados antes de alterar.
5. **Rastreabilidade sem dados privados:** correlacionar pedido, alteração, teste, resultado e evidências de forma segura.
6. **Simplicidade operacional:** manter um app Android modular, sem impor microserviços, protobuf, Go ou arquitetura distribuída.
7. **Segurança por padrão:** segredos fora do repositório e do histórico; auditoria de vazamento no commit, push e CI.
8. **Decisões abertas são abertas:** não transformar hipóteses desta análise em decisões definitivas.

## Matriz de transferência: YB Convênio → WA-Keeper

**Leitura:** `Adotar` significa transferir o princípio; `Adaptar` significa preservar a intenção mudando a implementação; `Não transferir` evita acoplamento indevido. **Esta tabela é uma proposta documental, não uma refatoração aprovada.**

| Princípio no YB Convênio | Origem / fundamento | Aplicação no WA-Keeper | Decisão proposta | Validação necessária |
| --- | --- | --- | --- | --- |
| Fronteiras de domínio explícitas | `architecture/01-principles.md`: monólito modular, módulos em código | Captura, conversas/identidade, memória, mídia, transcrição, agendamento, backup, apresentação | **Adotar** | Mapear chamadas reais, dono de cada regra e fluxo atravessando domínios |
| Domínio próprio controla suas regras | `architecture/01-principles.md`: negócio próprio versus domínio clínico externo | WA-Keeper governa histórico/identidade/filas; WhatsApp e Android são integrações externas, não donos das regras internas | **Adaptar** | Identificar código de negócio dependente de serviços externos |
| Interfaces/adapters para terceiros | `architecture/01-principles.md`: adapters para ImageMais e demais provedores | Fronteiras para Android NotificationListener, envio WhatsApp/Business, Google Drive, motor ASR | **Adotar** | Inventariar dependências concretas e contratos; preservar integrações existentes |
| Identidade interna independente de fornecedor | `architecture/01-principles.md`: IDs de fornecedores não definem identidade interna | Chave canônica de conversa/entidade distinta de nome exibido, telefone e identificadores externos | **Adaptar** | Conferir `ConversationIdentity`, destinatário agendado, reimportação, deduplicação |
| Distinguir identidade de papéis | Modelo `Person != Patient` do YB, específico do negócio | Separar conceitualmente contato, conversa, grupo, entidade e associação; não presumir equivalência | **Adaptar** | Descrever cardinalidades, ciclos de vida e migrações existentes |
| Contratos entre consumidores e provedores | Separação APP/WEB/API do YB e seus contratos de comunicação | Contratos de dados e comportamento entre captura, armazenamento, interface, envio e backup | **Adaptar** | Levantar produtores/consumidores e testes de compatibilidade |
| Rastreabilidade de decisões e alterações | Auditoria e rastreabilidade do domínio próprio do YB | Diário de engenharia vinculado a tarefa, branch, SHA, baseline, teste, evidência e eventual revert | **Adotar** | Definir formato mínimo e resolver registros sem commit ou com histórico reescrito |
| Simplicidade, sem serviços prematuros | `architecture/01-principles.md`: serviços separados só por motivo operacional | Preservar aplicativo Android modular; separar responsabilidades lógicas antes de separar módulos físicos | **Adotar** | Justificar custo/benefício antes de dividir pacote, banco ou processo |
| Propriedade e isolamento dos dados | Sistema próprio versus dados clínicos externos | Determinar quem escreve/lê identidades, mensagens, mídias e filas; Drive é destino de backup | **Adaptar** | Levantar invariantes e garantias de consistência/restauração |
| Go, Flutter, Connect, protobuf, ImageMais, SUUID, domínio clínico | Decisões tecnológicas ou de negócio específicas do YB | Não correspondem ao WA-Keeper | **Não transferir** | Nenhuma |
| Prevenir publicação de segredos e operação destrutiva | Necessidade operacional do WA-Keeper, **não** cópia de arquitetura do YB | Bloqueios preventivos e recuperação; detalhes na seção Segurança | **Regra local complementar** | Revisar bypasses, escopo Git/CI e resposta a incidentes |

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

## Análise aprofundada: identidade, contatos e conversas

**Estado:** levantamento do código em `development` em 2026-10-08; conclusões restritas aos arquivos examinados. Os comportamentos problemáticos indicados são **riscos a testar**, não defeitos já reproduzidos.

### Vocabulário do domínio e fronteiras

| Conceito | Identidade e ciclo de vida | Não confundir com | Evidência |
| --- | --- | --- | --- |
| Contato do dispositivo | Registro da agenda, com nome e um ou mais números; pode mudar externamente | Conversa do WhatsApp | `ContactDirectory.Entry`, `ContactsContract` |
| Identidade de conversa | Contexto de mensagens associado a pacote de origem, chave de conversa e título canônico | Nome de contato ou número de telefone | `ConversationIdentity.stableKey`, `groupKey`, `sameConversation` |
| Mensagem/notificação capturada | Evento persistido com remetente, pacote e chave de conversa | Conversa como entidade permanente | `NotifListenerService.onNotificationPosted` e `NotifEntity` |
| Entidade do usuário | Agrupamento escolhido pelo usuário que pode se relacionar a conversas/contatos | Identidade externa | `EntityAssociationsActivity`, `MemoryRepository` |
| Destinatário agendado | Alvo validado e persistido para um envio futuro; depende de integração externa | Título textual da conversa | `ScheduledMessageCoordinator`, `ScheduledMessageStore` |
| Identificador externo | Atalho/tag do Android, pacote, número ou título visível | Identificador canônico interno necessariamente imutável | `ConversationIdentity.stableKey`, `ContactDirectory` |

**Princípio transferido do YB:** identidade do domínio não deve ser ditada pelo identificador de um provedor. O equivalente de `Person != Patient` aqui **não é** uma mesma estrutura de classes, mas as distinções contato ≠ conversa ≠ entidade ≠ destinatário.

### Relações observadas (código)

1. `NotifListenerService.onNotificationPosted` extrai o título e chama `ConversationIdentity.canonicalSender` e `stableKey`. Também consulta `conversationBindings`: a captura participa da formação e resolução da identidade.
2. `ConversationIdentity.stableKey` prioriza `shortcutId`, depois `tag`, depois um fallback baseado em pacote e título canônico minúsculo. Logo, essa chave **pode depender de representação textual** quando as fontes mais estáveis não estão disponíveis.
3. `ConversationIdentity.conversationBuckets` agrupa inicialmente por pacote e título canônico; então separa pelas chaves presentes, juntando registros legados sem chave se houver apenas uma chave no grupo. Isso é uma política de migração/agrupamento implícita, não uma prova de identidade definitiva.
4. `ConversationIdentity.sameConversation` compara chaves se ambas estão presentes, senão recorre à equivalência dos títulos canônicos. Esse fallback pode ser deliberado para compatibilidade legada; demanda testes para homônimos.
5. `ContactDirectory` lê `ContactsContract`, normaliza os números e indexa por nome convertido em minúsculo. Um nome pode possuir vários números; nomes iguais podem resultar em candidatos agregados.
6. `ContactDirectory.refreshNow` percorre `conversationBindings`, atualiza números candidatos e só preserva `resolvedPhone` se ele continuar na lista atual. Aqui ocorre **uma escrita persistente provocada por atualização da agenda**, atravessando integração externa e domínio.
7. `ScheduledMessageCoordinator.onConversationActivity` solicita `store.nextEligible(packageName, conversationSender, conversationKey, at)`, mas o controle temporário de eco utiliza `packageName|conversationSender`. Vale verificar se conversas homônimas podem compartilhar indevidamente esse intervalo.
8. Na entrega, o coordenador escolhe entre `sendMedia`, `sendToPhone` e `send` com base nos dados persistidos. A escolha entre conversa e telefone, portanto, influencia a fronteira de envio.
9. `ScheduledMessageStore` é uma **porta explícita**; `RoomScheduledMessageStore` é o adapter Room. O contrato declara claim atômico, estados, retry, falha final e recuperação de claims presos. Preservar esse desenho.
10. `EntityAssociationsActivity` constrói candidatos de conversas e da agenda, inclusive aliases de contato. É importante estabelecer formalmente cardinalidades de vínculo, sem substituir uma entidade do usuário pelo nome que veio do aparelho.

### Invariantes propostas para futura aprovação

| ID | Invariante candidata | Verificação necessária |
| --- | --- | --- |
| ID-01 | Duas conversas distintas não devem ser fundidas exclusivamente porque seus títulos coincidem | Casos de homônimos com chaves externas diferentes e ausência de chave |
| ID-02 | Mudança de nome de contato não deve reatribuir silenciosamente uma conversa já resolvida a outro destinatário | Alteração da agenda, nomes repetidos e múltiplos números |
| ID-03 | Uma associação feita pelo usuário deve sobreviver a mudanças de exibição de nomes sempre que a identidade subjacente permanecer válida | Renomeação, reimportação e reinstalação com restauração |
| ID-04 | O destinatário de um agendamento precisa estar resolvido e validado antes de aceitar o agendamento, com política explícita para perda posterior da capacidade de envio | Criação, disparo, número alterado, conta inexistente e falha recuperável |
| ID-05 | A fila e o mecanismo de prevenção de eco não podem misturar duas conversas com nomes iguais | Triggers concorrentes, títulos iguais, pacotes diferentes, chaves diferentes |
| ID-06 | A transição de uma chave legada baseada em título para identificador estável deve preservar histórico sem fusão ou duplicação indevida | Banco anterior à migração, conversa com e sem chave |
| ID-07 | A falha de envio nunca deve ser representada como sucesso e não deve descartar o item recuperável sem regra explícita | Estados claim/retry/failed e ações de recuperação |
| ID-08 | Nenhuma alteração de identidade pode quebrar associações com entidades, filtros, mídia, backup ou agendamento | Testes de contrato dos consumidores |

Estas invariantes são **propostas**, não promessas de que o comportamento atual já as cumpre.

### Decisões arquiteturais a não antecipar

- Não introduzir SUUID, UUID novo nem migração de chaves antes de documentar os identificadores persistidos e a compatibilidade de backup.
- Não dividir `NotifDatabase` ou criar módulos físicos apenas para cumprir um organograma.
- Não remover fallbacks legados sem entender por quais fluxos de importação e restauração os registros chegam.
- Não alterar `ScheduledMessageCoordinator` antes de descrever todos os caminhos da escolha do destinatário até `ReplySender`.

**Próxima revisão:** inventariar `ConversationBinding`, `NotifEntity`, `ScheduledMessageEntity`, `ReplySender` e os DAOs respectivos, e produzir uma matriz `dado → proprietário → leitores → escritores → invariantes → testes`. Esta revisão continua documental.

## Definição operacional de domínio (revisão conceitual)

Neste projeto, **domínio** é uma unidade reconhecível de responsabilidade funcional, compreendendo seus dados, operações, invariantes, eventos e contratos de acesso, independentemente das classes que o implementam. Uma classe pode implementar parte de um domínio; um domínio pode atravessar várias classes; uma classe existente pode estar acoplando vários domínios. **Mudanças no comportamento ou contrato de um domínio requerem análise de impacto e autorização explícita**, não apenas revisão de arquivos.

Exemplo **Contatos**: importar agenda inicialmente, observar atualizações da agenda, manter snapshot, consultar nome/números candidatos e fornecer desambiguação. Essas operações devem ter dono funcional único, ainda que sejam implementadas por serviços, observers, repositórios e interfaces distintos. A decisão de atualizar automaticamente um vínculo já resolvido é um contrato **entre Contatos e Identidade de Conversas**, não apenas um detalhe de `ContactDirectory`.

### Catálogo inicial de modelos persistidos (Room, schema v14)

Fonte: `NotifDatabase.kt`, `ScheduledMessage.kt`, `MemoryRepository.kt`, `ScheduledMessageCoordinator.kt`, `ReplySender.kt`. **Proprietário** significa domínio responsável pelo contrato; não necessariamente classe que declara a tabela. Leitores/escritores indicam evidências observadas, **não inventário exaustivo de todas as referências**.

| Modelo / chave | Domínio proprietário proposto | Escrita observada / porta | Leitores e consumidores observados | Contrato e risco |
| --- | --- | --- | --- | --- |
| `NotifEntity` (`notifications.id`, `fingerprint` único; `conversationKey` indexado) | Captura e histórico de mensagens | `NotifDao.insert/insertIgnore`, `NotifListenerService`; `NotifDao.setAudioPath/setImagePath/setTranscript` | `ConversationActivity`, `ConversationIdentity`, `MemoryRepository`, backup, transcrição | Evento e identidade da conversa; alterações em `sender`/`conversationKey` repercutem em consumidores e histórico; transcript e paths são escritas de outros domínios |
| `ConversationBindingEntity` (PK `packageName+conversationKey`) | Identidade de conversas / resolução | `ConversationBindingDao.upsert`, `NotifListenerService`, `ContactDirectory.refreshNow` | `ContactDirectory`, captura e fluxos de resolução | `sender`, `isGroup`, `candidatePhones`, `resolvedPhone`; mudança na agenda escreve no vínculo e pode invalidar resolução anterior |
| `MemoryEntity` (`memory_entities.id`) | Entidades e memória do usuário | `MemoryDao.insertEntity/updateEntity/deleteEntity` via `MemoryRepository` e interfaces | `EntityAssociationsActivity`, `MemoryRepository` | Identidade própria da entidade, distinta de título/telefone e de conversa |
| `EntityLinkEntity` (`entity_links.id`, índice **único** em `packageName+sender`) | Associações entre entidades e conversas/contatos | `MemoryDao.upsertLink/unlink/moveAllLinks`, `MemoryRepository`, `EntityAssociationsActivity` | `MemoryRepository`, associação e contexto | Vínculo modelado por pacote+sender+role; ausência de `conversationKey` no índice requer verificar colisões por homônimos antes de qualquer mudança |
| `ScheduledMessageEntity` (`scheduled_messages.id`, índices por conversa e hora) | Agendamento e envio | `ScheduledMessageDao`, `RoomScheduledMessageStore`, coordenador | `ScheduledMessagesActivity`, `ScheduledMessageCoordinator`, disparadores | Estado PENDING/CLAIMED/SENT/FAILED/CANCELLED; `sender`, `conversationKey` opcional, `recipientPhone` opcional; sequência, claim e recuperação não devem perder identidade |
| `TranscriptionRunEntity` (`transcription_runs.id`, `notificationId` indexado) | Transcrição e avaliação | `TranscriptionRunDao.insert/rate` | `TranscriptionRunDao.latestForNotification/statsByMethod`, histórico de transcrição | Ancorar execução ao áudio/notificação correto; índice não declara integridade referencial entre entidades |
| `ConversationSettings` (PK `sender`) | Preferências e retenção de conversas | `SettingsDao.upsert/delete` | `SettingsDao.get/getAll`, políticas de retenção | Chave apenas por `sender`, sem `packageName` ou `conversationKey`; verificar separação WhatsApp/Business e homônimos |

### Fluxos que atravessam fronteiras

| Origem da operação | Fluxo atual identificado | Contrato entre domínios a especificar |
| --- | --- | --- |
| Agenda do aparelho | `ContactDirectory.refreshNow` → `conversationBindings.all/upsert` | Contatos propõe candidatos; Identidade de Conversas governa vínculo e desambiguação persistida |
| Notificação recebida | `NotifListenerService` → `ConversationIdentity` → `NotifDao` / `ConversationBindingDao` | Captura não deve confundir título visual, remetente canônico e identidade persistente |
| Associação de entidade | `EntityAssociationsActivity` → `MemoryRepository` / `MemoryDao` | Entidades controlam seus vínculos; modelos de contato/conversa não podem perder distinção |
| Envio agendado | `ScheduledMessageCoordinator` → `ScheduledMessageStore` → `ReplySender` | Estado, destino validado, claim, tentativas, ordem e resultado observável |
| Persistência do áudio/transcrição | `NotifDao` + `TranscriptionRunDao` | Registros de avaliação devem pertencer à notificação/áudio correto |
| Retenção e preferências | `ConversationSettings` / `SettingsDao` + eventos `NotifEntity` | Critério de configuração deve identificar inequivocamente o alvo da retenção |

### Achados concretos e perguntas para validação

- `ConversationBindingEntity` possui chave composta por pacote e chave de conversa; isso é uma distinção mais forte do que apenas título.
- `EntityLinkEntity` usa índice único `packageName+sender`, sem chave de conversa. **Risco:** restrição de uma associação distinta para duas conversas homônimas no mesmo aplicativo. Confirmar regras de negócio e caso real antes de propor migração.
- `ConversationSettings` tem chave apenas `sender`. **Risco:** retenção compartilhada inadvertidamente entre contas/aplicativos e homônimos. Verificar comportamento.
- O método `NotifDao.conversationFlow(sender,pkg)` consulta nome e pacote, não `conversationKey`. **Risco:** apresentar mensagens de conversas homônimas juntas em determinadas telas. Precisa de rastreamento completo dos consumidores.
- `ScheduledMessageDao.nextEligible` filtra pacote+sender e aceita `conversationKey IS NULL` ou chave igual. Isso preserva legado, mas exige testes de ausência e colisão de chaves.
- `NotificationReplySender.sendToPhone` declara que telefone é **metadado de desambiguação**, depois chama `send(packageName,sender,text)`. Assim, a entrega real depende de resolução pelo título/ação de notificação; exigir evidência da seleção correta, não assumir que o telefone é roteamento.
- `ScheduledMessageEntity` admite edição e envio imediato em PENDING/FAILED, e DAO inclui ações apropriadas. Confirmar exposição na UI, não inferir a partir da estrutura do modelo.
- `NotifDatabase` está na versão 14 e `exportSchema=false`; qualquer evolução dos identificadores persistidos requer avaliação prévia de migrações e recuperação de backups.

### Regra de autorização de domínio (proposta, ainda não automatizada)

Uma mudança que altere **contrato observável, identidade, persistência, regras de negócio ou comportamento aprovado** de um domínio requer: descrição do requisito; lista de domínios afetados; matriz de consumidores; avaliação de compatibilidade; autorização do operador; e depois evidência de validação. Refatoração interna sem alteração de contrato segue revisão técnica e testes, sem transformar cada mudança de classe em aprovação manual. O objetivo é aprovar **mudanças de responsabilidade/contrato**, não microgerenciar arquivos.

### Pendências

- Completar mapa de leitura/escrita por busca de todas as chamadas dos DAOs no repositório.
- Verificar fluxos de importação, exportação e restauração frente às chaves de conversa e vínculos legados.
- Elaborar cenários exemplares de duas conversas homônimas, contato renomeado, múltiplos números e WhatsApp/Business.
- Validar com testes existentes quais invariantes já são comprovadas. **Nenhuma alteração de código nesta etapa.**

## Governança cruzada: domínios × features × unidades de alteração

**Definições:** domínio é uma fronteira funcional estável (dados, operações, invariantes e contratos); feature é uma capacidade de produto que pode consumir vários domínios; *work item* é uma mudança delimitada em uma feature, implementada em branch isolada. A branch **não é a feature**. Um mesmo domínio participa de várias features e uma feature usa vários domínios.

### Matriz de relações (hipóteses para catalogação, não permissões)

| Domínio candidato | Captura | Envio agendado | Transcrição | Backup |
| --- | --- | --- | --- | --- |
| Identidade e conversas | Consome | Consome | Consome | Consome |
| Contatos | Consome | Consome | - | - |
| Captura e mensagens | Principal | Consome | Consome | Consome |
| Agendamento e envio | - | Principal | - | - |
| Mídias | Consome | Consome | Consome | Consome |
| Transcrição | - | - | Principal | Consome |
| Backup e restauração | - | - | - | Principal |

`Principal` significa responsabilidade funcional típica; `Consome` é dependência possível, não licença de edição. Domínios e features devem ter IDs estáveis e descrição de seus contratos; a matriz real será preenchida com base nos consumidores observados.

### Permissão por implementação e por domínio

Cada work item registra: `id`, `feature_id`, `objective`, `branch`, `base_sha`, `primary_domain`, `affected_contracts`, `authorized_domain_writes`, critérios de aceitação, evidências e estado.

- A branch nasce a partir da branch de integração atualizada, com **uma alteração delimitada em uma feature** e um domínio principal autorizado para escrita **somente no escopo dessa alteração**.
- Leitura, inspeção e testes em outros domínios são permitidos; **edição** de comportamento/contrato/código pertencente a outro domínio exige autorização explícita do operador, com justificativa registrada.
- A permissão adicional persiste **somente até o fechamento da branch/work item**, não se estende à próxima branch nem autoriza modificações não relacionadas dentro do mesmo domínio.
- Se o pedido introduzir **outra feature ou outro objetivo independente**, suspender essa parte, registrar novo work item e criar branch independente. Não misturar alterações, mesmo quando ambas usam um arquivo compartilhado.
- Arquivos não são um proxy perfeito para domínios: arquivos compartilhados exigem mapa de propriedade por símbolo/contrato e revisão do diff; arquivos gerados, migrações e integrações podem atravessar limites.
- A necessidade de um teste de consumidor não concede automaticamente permissão para modificar sua implementação. Uma correção necessária nesse consumidor deve ser explicitamente autorizada ou separada em trabalho dependente.
- Autorizações e alterações ficam no diário, vinculadas aos SHAs dos commits; revisão deve verificar tanto **domínio autorizado** quanto **objetivo/feature do work item**.
- Branch passa por testes, revisão e aprovação antes de integrar; depois da integração confirmada, registra `merge_sha` ou commits resultantes (incluindo squash), encerra o work item e elimina a branch temporária, preservando histórico Git e diário.
- A próxima alteração na mesma feature recebe **novo work item e nova branch**. Branch abandonada não deve ser considerada entregue; deve ter estado de cancelamento e documentação de seus commits/referências antes de limpeza.

### Políticas e casos difíceis

**Escopo é a primeira proteção:** autorizar outro domínio não significa aprovar qualquer melhoria descoberta nele. A permissão é a interseção entre `objetivo da alteração` e `domínios autorizados`.

**Concorrência:** duas branches podem trabalhar na mesma feature com work items independentes, mas alterações incompatíveis em contrato compartilhado exigem coordenação, rebase/merge e nova verificação, nunca integração cega.

**Alterações transversais:** ajustes de infraestrutura, segurança, build e migrações podem não ter domínio funcional principal; criar categoria própria de trabalho transversal com lista explícita de domínios afetados, e não classificá-las artificialmente como feature de usuário.

**Urgência:** incidentes de produção podem demandar hotfix, ainda com work item, branch e evidência mínimos, aprovação excepcional registrada e revisão posterior.

**Aplicação futura:** regras documentais não bloqueiam comandos por si sós. Detecção automática por caminhos/arquivos é apenas um sinal preliminar; verificação de escopo semântico requer diff, propriedades de domínio e revisão. O mecanismo de fiscalização é tratado em conversa separada.

### Exemplo de manifesto documental (formato proposto)

```yaml
work_item: WK-042
feature_id: scheduled-messages
objective: recover-failed-message
branch: feature/WK-042-recover-failed-message
primary_domain: scheduling
base_sha: <record-at-branch-creation>
authorized_domain_writes:
  - domain: scheduling
    scope: recover-failed-message
additional_authorizations: []
status: active
```

**Não confundir este modelo com um arquivo já existente ou um gate já implementado.** O exemplo só ilustra o contrato a validar.

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

## Segurança e continuidade histórica

### Política de prevenção (não reconstruir história como rotina)

- **Não publicar informação sensível:** credenciais, arquivos de configuração privados, tokens e dados pessoais ficam fora do repositório. Preferir referências locais e arquivos de exemplo sem segredo. `.gitignore` sozinho é insuficiente para arquivos rastreados.
- **Bloqueios em camadas:** varredura de segredos em preparação/commit, antes de push e no CI. Inspecionar também o conjunto de commits a publicar, não só a versão final dos arquivos; nunca imprimir o próprio segredo em logs.
- **Não permitir operações destrutivas como rotina:** proibir em automações e scripts de uso normal `push --force`, `push --mirror`, `reset --hard` com descarte de trabalho, limpeza irreversível de refs e reescrita de histórico. Uma medida excepcional requer autorização humana específica, backup recuperável e plano de restauração.
- `publisher/scripts/purge-sensitive-history.sh` foi **medida emergencial histórica**, não procedimento recomendado. Sua existência não autoriza execução automática; deve ser classificado e isolado como perigoso.
- O operador pode executar comandos manualmente: barreiras técnicas **reduzem risco**, não alegam impedir toda ação do proprietário. Regras de branches protegidas no provedor adicionam defesa independente.
- Se segredo tiver sido exposto, **revogar/rotacionar** imediatamente; remover histórico sem rotacionar não corrige o comprometimento.

### Diário amarrado ao Git

Registrar por mudança: `change_id`, data, requisito/problema, decisão e justificativa, domínios afetados, branch, `base_sha`, `implementation_sha` (ou lista de SHAs), testes/evidências, aprovação e estratégia de rollback/recovery. O SHA identifica código, mas o diário explica a intenção.

- Antes do primeiro commit, admitir estado `PLANNED` com `implementation_sha: null`; jamais inventar SHA.
- Ao integrar, registrar `merge_sha`, tag/release quando houver e `supersedes` quando outra correção substitui a anterior.
- Usar reverts rastreáveis em vez de `reset --hard` em trabalho compartilhado.
- Se houver reescrita excepcional do histórico, registrar mapeamento antigo→novo quando disponível e preservar diário sanitizado fora dos commits descartados.
- Espelho Git local durável **não substitui backup independente** (outro disco/host). Testar restauração periodicamente. Credenciais têm backup separado e criptografado.
- Nunca armazenar conversas, áudios, tokens e chaves privadas no diário ou nas evidências publicadas.

## Próxima análise documental

1. Confirmar cardinalidades, responsáveis e limites reais de cada domínio com leitura dos fluxos de código existentes.
2. Classificar linha por linha a matriz acima como `CONFIRMADO`, `A VALIDAR` ou `REJEITADO`.
3. Documentar contratos existentes e dependências indevidas sem alteração de código nesta etapa.
4. Identificar mecanismos atuais de proteção de credenciais e histórico, inclusive lacunas, **sem executar operações destrutivas**.
5. Manter especificação e implementação do Goals Kit Watchdog **fora deste chat**.
