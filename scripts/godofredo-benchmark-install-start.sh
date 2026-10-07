#!/usr/bin/env bash
set -Eeuo pipefail
APP_ID="br.com.wanotifkeeper.godofredo.benchmark"
APK="godofredo-benchmark/build/outputs/apk/debug/godofredo-benchmark-debug.apk"
fail(){ printf 'ERRO: %s\n' "$*" >&2; exit 1; }
log(){ printf '==> %s\n' "$*"; }
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"
command -v adb >/dev/null 2>&1 || fail "adb não encontrado"
ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=( -s "$ANDROID_SERIAL" )
else
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  [[ ${#DEVICES[@]} -eq 1 ]] || fail "conecte exatamente um Android ou use ANDROID_SERIAL=<serial>"
  ADB+=( -s "${DEVICES[0]}" )
fi
log "Compilando APK paralelo do Godofredo"
bash scripts/build.sh --godofredo-benchmark
[[ -f "$APK" ]] || fail "APK não encontrado: $APK"
log "Instalando sem tocar no WA Keeper"
"${ADB[@]}" install -r "$APK" >/dev/null
if [[ "${GODOFREDO_KEEP_EVIDENCE:-0}" != "1" ]]; then
  log "Limpando evidências antigas do benchmark"
  "${ADB[@]}" shell pm clear "$APP_ID" >/dev/null || true
fi
log "Abrindo Godofredo Benchmark"
"${ADB[@]}" shell monkey -p "$APP_ID" 1 >/dev/null 2>&1 || true
cat <<'EOF'

PRONTO PARA TESTE
1. Toque OUVIR.
2. Diga uma instrução completa.
3. Repita várias vezes, inclusive após alguns segundos de silêncio.
4. O WA Keeper principal continua instalado e independente.

Ao terminar:
  bash scripts/godofredo-benchmark-collect.sh
EOF
