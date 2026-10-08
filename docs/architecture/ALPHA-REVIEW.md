# WA-Keeper — revisão arquitetural Alpha (2026-10-08)

**Status:** Alpha documental, aberta à crítica; nenhuma refatoração, bloqueio ou mudança de produção aprovada. A Alpha está na branch documental `docs/governance-watchdog-domain-baseline`. A expressão **future branch** usada nas discussões designa futuras branches temporárias de implementação de features, não uma branch literal chamada `future`.

**Fonte:** princípios do YB Convênio (`architecture/01-principles.md`, `architecture/02-domain-and-identifiers.md`) adaptados à base Android do WA-Keeper.

**Documento detalhado:** [domain-baseline-watchdog.md](domain-baseline-watchdog.md).

## Compromissos arquiteturais desta Alpha

1. **Domínio** é dono reconhecível de responsabilidades, dados, operações, invariantes e contratos. Transversal às classes; não equivale a classe, pacote ou tabela.
2. **Feature** entrega uma capacidade do produto e pode atravessar vários domínios, sem adquirir suas permissões de escrita.
3. **Work item / branch** representa **uma alteração delimitada de uma feature**, com objetivo e critérios explícitos. Outras features ou objetivos independentes não entram na mesma branch.
4. **Escopo de escrita** nasce no domínio principal, restrito ao objetivo do work item. Outros domínios podem ser lidos e testados; suas implementações **somente podem ser modificadas mediante autorização expressa**, persistente até o fechamento dessa branch, mas sem autorizar mudanças alheias ao objetivo.
5. **Infraestrutura técnica pertence aos domínios**: banco, arquivos, telas, integração, configuração e testes não são zona livre. Em componentes compartilhados, a propriedade é segmentada por responsabilidade/contrato; alterações transversais exigem identificar e autorizar todos os domínios atingidos, sem burocracia artificial.
6. **Identidade própria e IDs externos** ficam separados; nunca importar a tecnologia SUUID, o vocabulário clínico ou a estrutura de serviços do YB.
7. **Contratos aprovados** e preservação de dados prevalecem sobre refatoração. Gate automático ainda **não existe**; a Alpha documenta obrigações, não declara enforcement.
8. **Git + diário de engenharia:** work item → feature/domínios → branch → base SHA → commits → testes/evidências → aprovação → integração/merge SHA → encerramento/remoção da branch. A remoção da branch não deve apagar histórico.
9. **Segredos e ações destrutivas:** nunca incluir segredos no Git; tratar varredura preventiva e bloqueio de comandos destrutivos como controles necessários, mas **ainda não instalados nesta etapa**. `purge-sensitive-history.sh` é resposta emergencial anterior.
10. **Watchdog** possui conversa e implementação próprias; este conjunto documental se restringe a princípios derivados do YB e aplicados ao WA.

## Matriz de transferência e classificação

| Do YB Convênio | Para WA-Keeper | Resultado |
| --- | --- | --- |
| Domínios com fronteiras e invariantes | Contatos, Conversas, Captura, Agendamento, Mídia, Transcrição, Entidades, Backup | **Adaptar e mapear** |
| Próprias identidades ≠ papéis/IDs externos | Contato ≠ conversa ≠ entidade ≠ destinatário; `shortcutId` e `tag` são referências externas | **Adaptar** |
| Adapters dos fornecedores | Serviços Android, WhatsApp/Business, Drive, ASR | **Adotar onde necessário** |
| Monólito modular | App Android com responsabilidades coesas, sem microserviços | **Adotar princípio, não stack** |
| Independência de dados externos | Vínculos e identidade internos não devem depender só de títulos/telefones | **Adaptar** |
| Contratos de comunicação / dependência | Interfaces entre consumidores dos domínios e implementações técnicas | **Adaptar** |
| Rastreabilidade | Work item, diário e SHAs, evidência de comportamentos aprovados | **Adotar** |
| Go, Flutter, Connect, SUUID, ImageMais | Sem correspondência técnica apropriada | **Não importar** |

## Problemas observados / riscos a validar

- `ContactDirectory.refreshNow` atualiza `conversation_bindings`. A agenda atravessa fronteira funcional de identidade persistida.
- `EntityLinkEntity` tem chave única `packageName+sender`, sem `conversationKey`; avaliar homônimos.
- `ConversationSettings` usa somente `sender`; avaliar separação entre pacotes/contas.
- `ScheduledMessageDao.nextEligible` utiliza remetente e chave opcional; a compatibilidade legada pode misturar casos ambíguos.
- `NotificationReplySender.sendToPhone` trata telefone como metadado, não como rota de envio; exige validação do destino.
- `NotifDatabase` contém tabelas de diversos domínios, o que **não** o torna proprietário universal de seus contratos.

Nenhum dos riscos acima é declarado defeito reproduzido sem teste. São hipóteses ligadas a trechos observados.

## Deliberações da revisão Alpha (2026-10-08)

| Item | Decisão | Situação |
| --- | --- | --- |
| Fronteiras de Contatos, Conversas e Entidades | **Três domínios distintos**, mesmo com relações e dados compartilhados | **Aprovado conceitualmente** |
| Autorização de escrita em outro domínio | Autorização explícita, restrita à alteração da feature em sua branch, válida somente até encerrá-la | **Aprovado conceitualmente** |
| Diário e Git | Referenciar commits/integração e preservar rastreabilidade após excluir branch temporária | **Aprovado conceitualmente** |
| Propriedade de código/infraestrutura compartilhada | Não se pode inferir domínio proprietário a partir do arquivo; é preciso discutir granularidade e fluxo de autorização sem sobreengenharia | **Aberto, prioritário** |

Não inferir aprovação de mudanças de código ou de regras ainda não discutidas a partir dessas decisões.

## Pontos de crítica conjunta

1. **Resolvido para esses três:** Contatos, Conversas e Entidades são domínios distintos; completar seus contratos e pontos de integração.
2. Como documentar a propriedade segmentada de arquivo/classe/tabela compartilhada sem microgerenciar símbolos?
3. Quais mudanças em contratos compartilhados exigem aprovação expressa mesmo dentro do domínio principal? **Ainda em aberto.**
4. O work item transversal deve possuir domínio principal ou uma lista explícita de proprietários?
5. Quais invariantes de identidade e envio agendado devem virar testes antes de qualquer refatoração?
6. **Princípio aprovado:** diário durável vinculado a commits integrados; falta definir localização e formato.
7. Há conflitos entre esta Alpha e as regras de `AGENTS.md` ou `validated-features.md` a reconciliar antes do merge?

**Critério de saída da Alpha:** respostas às dúvidas, catálogo de propriedade revisado, invariantes aprovadas e nenhum conflito com contratos PROTECTED. Somente depois planejar mudanças de código.