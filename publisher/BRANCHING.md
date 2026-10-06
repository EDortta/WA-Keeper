# Estratégia de branches

## Branches permanentes

### development

Laboratório do WA Keeper.

Recebe:
- recursos novos;
- experimentos;
- capacidades que podem nunca ser aceitas pela Google Play;
- testes arquiteturais;
- protótipos.

Não é referência de publicação.

### play

Candidato permanente à Google Play.

Recebe somente:
- recursos aceitos para distribuição pela loja;
- correções destinadas à próxima publicação;
- documentação e declarações compatíveis com o binário submetido.

É a branch usada para testes de publicação, geração do AAB e tracks de teste.

### main

Produção oficial.

Deve corresponder ao código efetivamente publicado na Google Play.

Regras:
- não recebe commit direto;
- só recebe merge vindo de play;
- cada publicação recebe tag semântica, por exemplo v1.1.0.

## Fluxo

feature/* -> development

Quando uma funcionalidade também é Play-safe:

feature/* -> development
feature/* -> play

Quando é experimental ou incompatível com a Play:

feature/* -> development apenas

Depois:

play -> main -> tag de release

## Princípio

Nunca fazer merge automático de development para play.

A branch development contém deliberadamente recursos que podem não ser publicáveis.
