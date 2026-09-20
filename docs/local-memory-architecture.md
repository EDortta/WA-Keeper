# Memória local e entidades transversais (#49)

A memória do WA Keeper é uma camada sobre o banco Room já existente.

## Modelo

- notifications: mensagens retidas e importadas, com origem rastreável.
- memory_entities: pessoa, empresa ou projeto definido pelo usuário.
- entity_links: liga uma entidade a uma ou mais conversas, independentemente de conta/número/grupo.
- MemoryRepository: API única para criar entidades, associar conversas e recuperar contexto.

## Regra central

O LLM não é a memória. Ele apenas raciocina sobre mensagens devolvidas por MemoryRepository.retrieveContext().

A recuperação retorna contexto em ordem cronológica e mantém o ID da mensagem para auditoria/rastreio.

## Importação (#50)

O app aparece como destino de compartilhamento para TXT/ZIP exportado pelo WhatsApp. Cada mensagem recebe fingerprint SHA-256 com conversa + autor + timestamp + texto normalizado. Duplicatas são ignoradas.

A importação cria uma entidade/vínculo inicial para o histórico. Futuramente a UI poderá juntar esse vínculo a uma entidade já existente.

## Integração com resposta/agendamento

A camada não envia mensagens. O fluxo esperado é:

1. resolver entidade pela conversa atual;
2. recuperar contexto;
3. gerar sugestão local;
4. usuário confirma;
5. enviar agora ou entregar ao agendador existente.

Isso mantém o requisito de sugestão-first e evita envio autônomo.
