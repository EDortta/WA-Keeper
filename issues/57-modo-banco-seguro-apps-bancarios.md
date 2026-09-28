# Issue #57 — Modo Banco: reduzir conflito do WA Keeper com apps bancarios

## Contexto

Na triagem ADB de 2026-09-28, o WA Keeper foi identificado no Android como:

- pacote: `br.com.wanotifkeeper`
- servico de acessibilidade ativo: `br.com.wanotifkeeper/.MediaShareAccessibilityService`
- notification listener ativo: `br.com.wanotifkeeper/.NotifListenerService`
- permissoes concedidas: `READ_CONTACTS` e `RECORD_AUDIO`
- servico de acessibilidade com `retrieveInteractiveWindows=true`
- eventos de acessibilidade observados: `TYPE_WINDOW_STATE_CHANGED` e `TYPE_WINDOW_CONTENT_CHANGED`

Esse conjunto e funcionalmente esperado para o WA Keeper, mas tambem e uma
superficie que apps bancarios e SDKs antifraude costumam tratar como risco.
O caso observado pelo operador e o app Sicredi (`br.com.sicredimobi.smart`).

O objetivo nao e esconder o WA Keeper de apps bancarios. O objetivo e oferecer
um modo seguro honesto, controlado pelo usuario, que desligue ou pause as
superficies sensiveis antes do uso do banco e facilite a retomada depois.

## Objetivo

Implementar um "Modo Banco" / "Modo Seguro" no WA Keeper para reduzir a chance
de apps bancarios classificarem o WA Keeper como ameaca enquanto o usuario usa
o banco.

## Escopo esperado

O app deve oferecer uma experiencia clara para:

1. pausar qualquer automacao/processamento ativo do WA Keeper;
2. parar servicos foreground relacionados ao funcionamento do app, quando
   aplicavel;
3. orientar o usuario a desativar temporariamente:
   - Acessibilidade do WA Keeper;
   - Acesso a notificacoes do WA Keeper;
   - outras permissoes sensiveis, se houver evidencia de que o app bancario
     ainda bloqueia;
4. oferecer atalhos para as telas corretas do Android sempre que possivel;
5. indicar o estado atual:
   - normal;
   - pausado;
   - aguardando desativacao de acessibilidade;
   - aguardando desativacao de acesso a notificacoes;
   - pronto para abrir o banco;
6. oferecer uma acao de "Retomar WA Keeper" depois do uso do banco;
7. evitar perda de dados durante a pausa/retomada;
8. deixar claro para o usuario que algumas permissoes especiais exigem acao
   manual no Android e nao podem ser desligadas/religadas automaticamente pelo
   app de forma confiavel.

## Fora de escopo

- Bypassar, mascarar, ocultar ou enganar checks antifraude de apps bancarios.
- Usar ADB, root, Shizuku ou APIs privilegiadas para alterar configuracoes sem
  acao explicita do usuario.
- Desinstalar o WA Keeper automaticamente.
- Desativar apps bancarios ou modificar comportamento do Sicredi.

## Hipotese tecnica

O Sicredi pode estar bloqueando ou degradando funcionamento ao detectar:

- servico de acessibilidade de terceiros ativo;
- notification listener de terceiros ativo;
- app com capacidade de observar mudancas de janela/conteudo;
- app com permissoes sensiveis adicionais.

Mesmo que o WA Keeper seja legitimo e proprio, o app bancario nao sabe essa
intencao. Ele enxerga capacidades instaladas/habilitadas no sistema.

## Implementacao sugerida

Adicionar uma tela ou fluxo "Modo Banco" com:

1. botao "Ativar Modo Banco";
2. confirmacao curta explicando que o WA Keeper sera pausado;
3. persistencia local de estado `bank_mode_enabled=true`;
4. cancelamento/pausa de workers, observers, jobs e foreground services
   relacionados ao processamento ativo;
5. link para configuracoes de acessibilidade do Android;
6. link para configuracoes de acesso a notificacoes do Android;
7. checagem de retorno ao app para revalidar se as permissoes especiais foram
   desativadas;
8. estado visual "Seguro para tentar abrir o banco" quando as superficies
   sensiveis estiverem inativas;
9. botao "Retomar WA Keeper" com atalhos inversos para o usuario reativar
   acessibilidade/notificacoes quando necessario.

## Validacao esperada

Executar no Android real conectado:

1. confirmar estado inicial do WA Keeper com acessibilidade/notificacoes ativas;
2. abrir Sicredi e registrar se ha alerta/bloqueio;
3. ativar Modo Banco;
4. desativar manualmente acessibilidade e notification listener do WA Keeper;
5. abrir Sicredi novamente;
6. registrar se o alerta/bloqueio desaparece;
7. retomar o WA Keeper;
8. confirmar que o app volta a processar notificacoes/fluxos esperados sem
   perda de dados.

## Criterios de aceite

- O usuario consegue pausar o WA Keeper antes de abrir app bancario.
- O app guia o usuario para desativar as permissoes especiais que o Android nao
  permite controlar programaticamente.
- O app mostra claramente quando ainda ha superficie sensivel ativa.
- A retomada e simples e nao apaga dados.
- O comportamento e documentado dentro do projeto.
- Nao ha tentativa de burlar deteccoes antifraude.

## Evidencias de referencia

Triagem local gerada em:

`/home/esteban/scripts/android-security-triage-20260928-094731/`

Arquivos relevantes:

- `RELATORIO.md`
- `dumpsys-accessibility.txt`
- `enabled-accessibility-services.txt`
- `enabled-notification-listeners.txt`
- `package-dumpsys-third-party-v2/br.com.wanotifkeeper.txt`
- `package-dumpsys-third-party-v2/br.com.sicredimobi.smart.txt`
- `sensitive-runtime-permissions-granted.tsv`

## Pergunta a responder durante a implementacao

A desativacao apenas da Acessibilidade ja libera o Sicredi, ou tambem e
necessario desativar o acesso a notificacoes do WA Keeper?

Essa resposta deve ser registrada em evidencia do projeto apos teste real.
