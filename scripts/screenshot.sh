#!/usr/bin/env bash
# Captura a tela do dispositivo Android conectado via adb.
# Uso: ./scripts/screenshot.sh [nome-do-arquivo]
set -euo pipefail

ADB="${ADB:-$(command -v adb || echo /opt/platform-tools/adb)}"
OUTDIR="${SCREENSHOT_DIR:-/tmp/wanotifkeeper-shots}"
mkdir -p "$OUTDIR"

name="${1:-shot-$(date +%Y%m%d-%H%M%S)}"
[[ "$name" == *.png ]] || name="$name.png"
out="$OUTDIR/$name"

if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "erro: nenhum dispositivo adb conectado" >&2
  exit 1
fi

"$ADB" exec-out screencap -p > "$out"
echo "$out"
