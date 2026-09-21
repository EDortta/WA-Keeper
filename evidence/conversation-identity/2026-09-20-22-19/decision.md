# Decisão técnica — issue #56

A captura real via `tools/diag-conversation-identity.sh` mostrou uma notificação de conversa do WhatsApp Business com:

- `shortcut=5514998354550-1585313840@g.us found valid? true`
- `Notification(... shortcut=5514998354550-1585313840@g.us ... flags=ONLY_ALERT_ONCE ...)`
- `android.conversationTitle=String (‎Esteban-VGaspar (2 mensagens))`
- `android.title=String (‎Esteban-VGaspar (2 mensagens): Carlos Eduardo Roman)`
- `android.isGroupConversation=Boolean (true)`

Também existe uma notificação summary `GROUP_SUMMARY` com `groupKey=group_key_messages`; ela não deve ser usada como identidade de conversa porque representa o grupo de notificações da conta, não o chat específico.

## Regra adotada

1. `notification.shortcutId` continua sendo a identidade primária: é o identificador técnico do chat (`...@g.us` em grupo) e permanece estável quando título, contador de mensagens e remetente visível variam.
2. `sbn.tag` permanece como fallback técnico quando `shortcutId` não vier.
3. título normalizado fica apenas como fallback legado/display.
4. a home e o seletor de entidades agora agrupam por `conversationKey` quando disponível; antes ainda agrupavam por título normalizado, o que permitia colidir grupos/contatos de mesmo nome e mascarava a chave técnica já persistida.
5. `sameConversation` agora, quando ambos os lados têm `conversationKey`, exige igualdade da chave técnica antes de cair para título legado. Isso evita abrir mensagens de outra conversa com mesmo nome.

## Regressão protegida

`ConversationIdentityTest` cobre:

- mesma `conversationKey` agrupando títulos variáveis;
- chaves diferentes com mesmo título não colapsando;
- limpeza de marca invisível LRM e sufixo `(N mensagens)` observado na evidência real.
