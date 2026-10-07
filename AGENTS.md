# AGENTS.md — WA-Keeper

## Regra principal: feature validada é contrato

Uma feature que foi testada e explicitamente aprovada pelo usuário torna-se **PROTECTED**.

A partir desse momento, nenhum agente pode remover, substituir, alterar comportamento, trocar implementação, mudar fluxo de UI ou degradar uma feature PROTECTED sem autorização explícita do usuário.

"Refatoração", "limpeza", "padronização", "tema", "migração", "otimização" ou "melhoria arquitetural" não são autorização.

## Antes de qualquer mudança

1. Ler `docs/validated-features.md`.
2. Identificar quais features PROTECTED podem ser afetadas direta ou indiretamente.
3. Preservar seus contratos.
4. Se a mudança exigir quebrar ou alterar um contrato, parar e pedir autorização explícita.

## Depois de qualquer mudança

Uma alteração só pode ser considerada pronta quando:

- build debug passa;
- build release passa;
- testes existentes passam;
- contratos PROTECTED afetados passam por smoke test;
- não houve perda de dados ou migração destrutiva;
- a feature nova foi validada no aparelho real quando depender de Android, ADB, permissões, lifecycle, notificações, áudio, banco ou armazenamento.

Compilar não prova ausência de regressão.

## Mudanças transversais

Alterações em qualquer um destes pontos exigem atenção especial porque podem quebrar features não relacionadas:

- `AndroidManifest.xml`
- `Application`
- lifecycle de Activity/Service
- Room/database/migrations
- SharedPreferences
- permissões
- temas globais / `AppCompatDelegate`
- armazenamento
- serviços em background
- build.gradle / dependências

Ao tocar nesses pontos, executar smoke tests das features PROTECTED relacionadas.

## Evidência

Não declarar "corrigido", "pronto", "funcionando" ou equivalente sem evidência.

Use a distinção:

- IMPLEMENTED: código alterado;
- BUILD-GREEN: compila/testes passam;
- DEVICE-VALIDATED: comportamento confirmado no aparelho real;
- USER-APPROVED: usuário aprovou;
- PROTECTED: feature aprovada e bloqueada contra alterações não autorizadas.

## Proibição de regressão silenciosa

Se uma mudança causar perda de comportamento previamente aprovado:

1. tratar como regressão;
2. restaurar o comportamento anterior primeiro;
3. só então reaplicar a mudança nova de forma compatível;
4. registrar a causa e criar/ajustar teste ou trava para impedir recorrência.
