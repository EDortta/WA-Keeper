# Última falha do android-deploy

- Data UTC: `2026-09-16T17:07:00Z`
- Branch testada: `feature/audio-arbiter-manual-tts`
- Commit testado: `98a499d695dd9bd46c34419fa8002e0ab3d946cb`
- Variante: `release`
- Etapa: `Instalação ADB`
- Código de saída: `1`
- Android: `SM-A175F`

```text
BUILD SUCCESSFUL in 913ms
49 actionable tasks: 2 executed, 47 up-to-date
==> Instalando sem apagar dados
Performing Streamed Install
adb: failed to install app/build/outputs/apk/release/app-release.apk: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package br.com.wanotifkeeper signatures do not match newer version; ignoring!]

A instalação falhou. NÃO desinstale o WA-Keeper para "resolver" assinatura incompatível:
a desinstalação apagaria o banco local. Corrija a assinatura/keystore e rode novamente.
```
