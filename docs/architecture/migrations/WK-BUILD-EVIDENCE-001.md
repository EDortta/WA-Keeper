# Evidências compartilhadas — build/deploy

Branch de implementação: `feature/shared-build-evidences` (isolada da migração de Conversas).

## Estrutura local

O publicador usa **uma cópia compartilhada** em `~/.local/state/development-evidences/repo`, uma fila em `~/.local/state/development-evidences/outbox` e exclusão mútua via `publish.lock`. Todos os worktrees e projetos podem reutilizar o mesmo destino remoto: `git@github.com:EDortta/development-evidences.git`.

Estrutura publicada: `<projeto>/<feature>/<branch-normalizada>/yyyy-mm-dd/<execução>/manifest.json`. O nome original da branch permanece no JSON. Registros são imutáveis, não sobrescritos. Publicação remota exige Git e SSH já configurados.

O clone é obtido automaticamente na primeira publicação. Não criar submódulos. Registros locais de build e logs brutos são mantidos fora do repositório central.

## Operação

```bash
./scripts/build.sh --app --lab --debug --evidence=remote
./scripts/android-deploy.sh --evidence=remote
python3 scripts/evidence.py flush
```

Sem `--evidence`, o padrão é `local` (configurável com `WA_EVIDENCE_MODE=remote`). O deploy usa o manifesto do último **APK compilado com sucesso nesse worktree** e chama exclusivamente `build.sh`. Uma compilação bem-sucedida via deploy atualiza esse manifesto. Falhas não o sobrescrevem.

O manifesto de build reside no diretório de metadados Git do worktree: `wa-build/last-success`. Ele guarda `profile`, `type`, `mode`, `target`, `features` e `commit`. Compilações independentes em worktrees distintos não compartilham esse arquivo.

Logs Gradle locais ficam em `~/.local/state/wa-keeper/build-logs/`. Logs completos do deploy permanecem no diretório temporário usado por `android-deploy.sh`. **Nenhum log bruto é publicado**. O repositório remoto recebe apenas metadados permitidos, sem caminhos absolutos, remetentes, transcrições, mensagens ou segredos.

## Restrições e pendências

- Esta branch introduz um publicador em Python reutilizável. Para outros projetos, adaptar o chamador para invocar `scripts/evidence.py record` com os mesmos parâmetros ou extrair o publicador para um pacote compartilhado em uma mudança separada.
- Ainda não foram executados builds, deploy em dispositivo ou testes de concorrência no ambiente do usuário. Status: **IMPLEMENTED / NOT VALIDATED**.
- Configurações de assinatura release **não** são armazenadas no manifesto nem no repositório de evidências.
- A publicação remota não é garantia de anonimização de conteúdo arbitrário: o publicador aceita somente um conjunto fixo de metadados, não arquivos privados.
- A fila deve ser monitorada: ausência de conectividade não descarta evidências, mas também não constitui confirmação de publicação.
- Em caso de erro de assinatura do aplicativo instalado, não desinstalar nem alterar identidade do pacote automaticamente.
