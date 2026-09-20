# #51 — Backfill de histórico além das notificações

## Decisão

O WA Keeper não deve ler banco privado do WhatsApp nem depender de engenharia reversa. O backfill suportado nesta etapa é importação voluntária pelo usuário, implementada na #50.

## Caminhos avaliados

### 1. Exportação voluntária — ADOTADO

Entrada por compartilhamento Android (ACTION_SEND/ACTION_SEND_MULTIPLE), aceitando TXT e ZIP. O histórico é processado localmente, normalizado, deduplicado e armazenado no mesmo banco Room das mensagens retidas.

Vantagens:
- consentimento explícito;
- offline-first;
- não depende de internals do WhatsApp;
- preserva data, autoria e referência de origem;
- sustentável entre versões.

Limitações:
- depende do usuário exportar;
- o nome do arquivo é usado como pista para a conversa;
- mídia dentro do ZIP não é ingerida nesta primeira etapa.

### 2. Dispositivos vinculados — NÃO ADOTADO NESTA FASE

O protocolo de dispositivos vinculados é controlado pelo WhatsApp e não existe API pública estável para um app Android de terceiros consumir todo o histórico pessoal. Implementar cliente próprio exigiria engenharia reversa e criaria risco de quebra e conformidade.

### 3. APIs oficiais Meta / WhatsApp Business — FORA DO ESCOPO DO HISTÓRICO PESSOAL

A plataforma oficial atende números/business workloads integrados à API e não substitui o histórico local de contas pessoais já existentes. Pode virar um conector opcional futuro para conversas originadas em um tenant Business autorizado.

### 4. AccessibilityService — NÃO USAR PARA BACKFILL

Acessibilidade é aceitável apenas para automações específicas já justificadas no produto. Fazer scroll/leitura da UI do WhatsApp para reconstruir histórico seria frágil, caro, difícil de auditar e inadequado como fundação de memória.

### 5. Banco privado / backups internos — PROIBIDO PELA ARQUITETURA

Não depender de acesso root, cópia do banco interno, chaves, formatos privados ou extração de backup criptografado.

## Contrato arquitetural

Toda mensagem de memória precisa manter sourceType, sourceRef, author, timestamp, fingerprint e NotifEntity.id como identidade local.

O LLM recebe apenas contexto recuperado do banco local. Ele não é memória nem fonte de verdade.

## Próximas extensões seguras

1. UI para unir uma importação a uma entidade já existente.
2. Importação de mídia do ZIP para o MediaVault.
3. Heurística de associação por participantes/nome sem substituir confirmação do usuário.
4. Conector oficial para WhatsApp Business quando houver tenant autorizado.
