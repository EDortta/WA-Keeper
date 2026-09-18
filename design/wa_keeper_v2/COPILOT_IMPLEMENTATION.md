# Instruções para o Copilot — WA Keeper UI v2

## Regra principal
NÃO reimplementar regras de negócio existentes.

Preservar:
- leitura em voz alta;
- retenção de mensagens;
- mensagens programadas;
- auto-respostas;
- comandos de voz;
- persistência local;
- permissões;
- leitura de mensagens;
- anexos;
- integrações já existentes.

Alterar principalmente:
- tema;
- cores;
- tipografia;
- componentes visuais;
- espaçamentos;
- ícones;
- navegação visual;
- estados vazios;
- consistência entre tema claro/escuro.

## Identidade
Evitar aparência de clone do WhatsApp.

Usar:
- azul profundo como identidade;
- cyan como cor de inteligência/automação;
- verde apenas para sucesso/online/ações externas relacionadas ao WhatsApp;
- ícones Material Symbols Rounded ou equivalentes;
- bordas 14–20 px;
- cards com baixa elevação;
- layout limpo e profissional.

## Arquitetura sugerida
- tema centralizado em `lib/core/theme/`;
- componentes reutilizáveis em `lib/shared/widgets/`;
- telas por feature;
- evitar cores hardcoded dentro das telas;
- usar `ThemeExtension` ou tokens quando necessário;
- manter suporte Material 3.

## Ordem recomendada
1. Criar tema claro/escuro.
2. Criar widgets base.
3. Migrar tela de conversas.
4. Migrar ajustes.
5. Migrar retenção.
6. Migrar detalhe de mensagem.
7. Migrar agendamento.
8. Migrar auto-respostas.
9. Migrar comandos de voz.
10. Migrar estatísticas/sobre.

## Testes visuais
Validar em:
- 360x800
- 412x915
- fonte do sistema 1.0x e 1.3x
- tema claro
- tema escuro
