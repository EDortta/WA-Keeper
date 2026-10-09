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


## Ciclo obrigatório de evidências

Toda feature nova nasce em estado **EM VALIDAÇÃO**.

Enquanto estiver EM VALIDAÇÃO:

- deve gerar evidências de uso suficientes para diagnosticar comportamento real;
- essas evidências precisam ser recolhíveis por script, sem depender de inspeção manual;
- o script deve produzir saída segura para versionamento, sem áudio bruto, textos privados, remetentes, credenciais ou outros dados sensíveis;
- registrar, quando aplicável: tentativa, sucesso/erro, tempos, parâmetros relevantes, estado anterior/posterior e contexto técnico necessário para reproduzir o problema;
- a coleta deve ser abundante o suficiente para comparar versões e detectar regressões;
- uma feature sem evidência coletável não pode ser considerada pronta para aprovação.

Depois que o usuário aprovar explicitamente a feature:

- ela passa a PROTECTED;
- a telemetria abundante pode ser reduzida;
- deve permanecer um log operacional mínimo e coletável por script para diagnóstico futuro;
- o histórico necessário para provar regressões não deve ser apagado sem autorização explícita.

Regra prática: antes de implementar uma feature nova, definir também como sua evidência será registrada e qual script a recolherá.

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

## Build profiles e feature flags

Toda feature nova deve ser registrada em `publisher/features.properties` antes de ser implementada.

Regra inicial: `storeAllowed=false`.

Somente após revisão explícita para Google Play a feature pode mudar para `storeAllowed=true`.

Builds devem usar `scripts/build.sh`. O perfil `--store` não pode habilitar features não autorizadas para loja.

Features condicionais devem obedecer aos macros `BuildConfig.WA_FEATURE_*`. Não basta esconder UI: código, permissões, serviços e dependências exclusivos devem ser isolados quando necessário.

## Compilador único

`scripts/build.sh` é o único ponto autorizado para compilação Android.

Nenhum outro script, workflow ou agente pode chamar diretamente tarefas Gradle de assemble/bundle. Scripts de deploy, benchmark, pacote e publicação devem delegar a `scripts/build.sh`.


## Fonte documental compartilhada com o Goals Kit Watchdog

Antes de qualquer alteração, além de `docs/validated-features.md`, consultar `docs/governance/index.md` **quando existir** e seguir as definições oficiais de domínios, features, contratos e política de inspeção ali referenciadas. Os arquivos `docs/governance/domains/`, `features/` e `contracts/` são definições documentais; a existência de um Markdown não comprova implementação nem aprovação de comportamento.

A issue #57 foi aceita conceitualmente como direção arquitetural: tipos e operações explícitos, encapsulamento por domínio e contratos estáveis, com rigor configurável (`off`, `advisory`, `strict`). **Não declarar validação automática nem impor bloqueios até existir implementação e configuração aprovada.** Preservar as regras PROTECTED anteriores.

O Goals Kit Watchdog navega e descobre essa documentação, não mantém uma cópia paralela de regras para agentes. O catálogo JSON local é reconstruível; Git registra o histórico; testes e evidências estabelecem a situação verificada.
