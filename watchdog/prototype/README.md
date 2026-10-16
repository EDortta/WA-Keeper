# Goals Kit Watchdog — protótipo de navegação

Protótipo HTML autônomo, **não conectado ao Git real**, sem dependências, sem build, sem alterações no aplicativo Android.

## Abrir

Abra `watchdog/prototype/index.html` diretamente em um navegador. Nenhum servidor necessário nesta etapa.

## Navegação consolidada

- Mapa de domínios é a tela inicial.
- Grade com coluna `Domínio` fixa; demais colunas podem ser ocultadas, restauradas ou redimensionadas.
- Clique em um domínio leva à tela completa de gestão, mantendo o domínio selecionado.
- Gestão possui navegação lateral `Features` / `Domínios` e abas `Descrição`, `Eventos`, `Código`, `Testes`.
- Botão `Mapa` retorna ao mapa geral sem descartar a revisão selecionada.
- A escolha de commit não muda a tela, mas representa mudança do estado exibido. Itens posteriores aparecem esmaecidos.
- Tema claro/escuro.
- Edição Markdown demonstrativa, apenas em memória, sem salvar.

## Limitações explícitas

O grafo, os commits, as datas e os dados são **fictícios**. As ramificações do grafo real ainda não estão implementadas. Nenhuma busca no código, histórico real, teste ou persistência é executada. O visual da árvore Markdown é provisório; ainda não interpreta blocos hierárquicos completos. A definição de API, CLI e modelo temporal ocorrerá após validação da UX.

**Guardrails e documentação arquitetural pertencem ao outro fluxo de trabalho; este protótipo só implementa a interface.**
