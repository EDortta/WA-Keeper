#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper.benchmark"
OUT_BASE="${ASR_DEVICE_BENCHMARK_DIR:-diagnostics/asr-device-benchmark}"
STAMP="$(date '+%Y-%m-%d-%H-%M-%S')"
OUT_DIR="$OUT_BASE/$STAMP"
LATEST="$OUT_BASE/latest"

fail() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }
log() { printf '==> %s\n' "$*"; }

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"

command -v adb >/dev/null 2>&1 || fail "adb não encontrado"
command -v tar >/dev/null 2>&1 || fail "tar não encontrado"

ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  SERIAL="$ANDROID_SERIAL"
  ADB+=( -s "$SERIAL" )
else
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case "${#DEVICES[@]}" in
    0) fail "nenhum Android autorizado conectado" ;;
    1) SERIAL="${DEVICES[0]}"; ADB+=( -s "$SERIAL" ) ;;
    *) fail "mais de um Android conectado; use ANDROID_SERIAL=<serial>" ;;
  esac
fi

"${ADB[@]}" shell pm path "$APP_ID" >/dev/null 2>&1 ||
  fail "aplicativo de benchmark não está instalado"

STATUS_JSON="$("${ADB[@]}" exec-out run-as "$APP_ID" cat files/benchmark/status.json 2>/dev/null || true)"
[[ -n "$STATUS_JSON" ]] || fail "status do benchmark ainda não existe"

STATE="$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("state","unknown"))' <<< "$STATUS_JSON")"
MESSAGE="$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("message",""))' <<< "$STATUS_JSON")"

log "Estado no celular: $STATE"
[[ -n "$MESSAGE" ]] && log "$MESSAGE"

if [[ "$STATE" != "completed" && "$STATE" != "failed" ]]; then
  echo
  echo "O benchmark ainda está rodando. Não vou remover o APK nem coletar dados incompletos."
  echo "Reconecte mais tarde e rode este mesmo script."
  exit 2
fi

mkdir -p "$OUT_DIR"
printf '%s\n' "$STATUS_JSON" > "$OUT_DIR/status.json"

log "Copiando estatísticas e transcrições do celular"
"${ADB[@]}" exec-out run-as "$APP_ID" tar -C files/benchmark -cf - . > "$OUT_DIR/benchmark.tar"
tar -xf "$OUT_DIR/benchmark.tar" -C "$OUT_DIR"
rm -f "$OUT_DIR/benchmark.tar"

DEVICE="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"

if [[ -f "$OUT_DIR/runs.tsv" ]]; then
  log "Gerando relatório"
  python3 scripts/asr-thermal-report.py "$OUT_DIR" --device "$DEVICE" >/dev/null
fi

rm -rf "$LATEST"
mkdir -p "$LATEST"
cp -a "$OUT_DIR"/. "$LATEST"/

log "Removendo aplicativo de benchmark"
"${ADB[@]}" uninstall "$APP_ID" >/dev/null || fail "falhou ao remover $APP_ID"

echo
echo "COLETA CONCLUÍDA"
echo "  Estado: $STATE"
echo "  Resultados: $OUT_DIR"
echo "  Último resultado: $LATEST"
echo "  APK de benchmark removido."
echo "  WA-Keeper permaneceu instalado durante todo o processo."
