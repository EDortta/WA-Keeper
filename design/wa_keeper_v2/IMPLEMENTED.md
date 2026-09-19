# WA Keeper v2 — implementação Android

Implementado na branch `development`.

## Adaptação técnica

A referência inicial foi escrita pensando em Flutter, mas o aplicativo real é Android nativo (Kotlin + XML). A identidade v2 foi aplicada diretamente sobre a stack existente, sem reimplementar lógica de negócio.

## Implementado

- tokens de cor centralizados em `res/values/colors.xml`;
- tokens equivalentes para modo escuro em `res/values-night/colors.xml`;
- tema Material Components Day/Night alinhado ao v2;
- azul `#0D6EFD` como identidade principal;
- cyan `#06B6D4` para automação/inteligência;
- verde reservado para estados positivos;
- superfícies, textos, bordas, erros e estados migrados para tokens;
- tela principal redesenhada com identidade própria do WA Keeper;
- busca, tabs, lista, estado vazio, FAB de voz e dica visual alinhados ao v2;
- telas de mensagem, retenção, agendamento e ajustes migradas para os novos tokens;
- novo ícone do aplicativo em azul, com escudo + conversa/check, sem copiar a identidade visual do WhatsApp;
- suporte a tema claro/escuro.

## Preservado

IDs de views e fluxos existentes foram mantidos para preservar handlers e regras de negócio Kotlin.

Não foram reimplementados:
- retenção;
- TTS;
- comandos de voz;
- mensagens programadas;
- anexos;
- persistência;
- permissões;
- envio/respostas.
