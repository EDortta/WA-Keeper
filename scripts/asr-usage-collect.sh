#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper"
OUT_BASE="${ASR_USAGE_EVIDENCE_DIR:-diagnostics/asr-usage}"
STAMP="$(date '+%Y-%m-%d-%H-%M-%S')"
OUT_DIR="$OUT_BASE/$STAMP"
LATEST="$OUT_BASE/latest"

fail() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }
log() { printf '==> %s\n' "$*"; }

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"

for cmd in adb python3 tar; do
  command -v "$cmd" >/dev/null 2>&1 || fail "$cmd não encontrado"
done

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
  fail "WA-Keeper não está instalado"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-asr-usage.XXXXXX")"
RESTORE_RELEASE=0

cleanup() {
  code=$?
  if [[ "$RESTORE_RELEASE" == "1" && -f app/build/outputs/apk/release/app-release.apk ]]; then
    "${ADB[@]}" install -r app/build/outputs/apk/release/app-release.apk >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
  exit "$code"
}
trap cleanup EXIT

log "Compilando build debug temporária e release"
./gradlew --console=plain :app:assembleDebug :app:assembleRelease >/dev/null

DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$DEBUG_APK" ]] || fail "APK debug não encontrado"
[[ -f "$RELEASE_APK" ]] || fail "APK release não encontrado"

DEVICE="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
ANDROID_VERSION="$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
APP_VERSION="$("${ADB[@]}" shell dumpsys package "$APP_ID" 2>/dev/null | awk -F= '/versionName=/{print $2; exit}' | tr -d '\r')"

log "Parando WA-Keeper para obter uma visão consistente do banco"
"${ADB[@]}" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true

log "Instalando build debug temporária sem apagar dados"
"${ADB[@]}" install -r "$DEBUG_APK" >/dev/null
RESTORE_RELEASE=1
"${ADB[@]}" shell run-as "$APP_ID" id >/dev/null 2>&1 ||
  fail "run-as indisponível após instalar a build debug"

log "Copiando somente o banco temporariamente"
"${ADB[@]}" exec-out run-as "$APP_ID" sh -c '
  cd databases || exit 44
  set --
  for f in wanotif.db wanotif.db-wal wanotif.db-shm; do
    [ -f "$f" ] && set -- "$@" "$f"
  done
  [ "$#" -gt 0 ] || exit 45
  tar -cf - "$@"
' > "$WORK/db.tar"

mkdir -p "$WORK/db" "$OUT_DIR"
tar -xf "$WORK/db.tar" -C "$WORK/db"
DB="$WORK/db/wanotif.db"
[[ -f "$DB" ]] || fail "wanotif.db não foi extraído"

log "Gerando evidência anonimizada"
python3 scripts/asr-usage-report.py \
  "$DB" \
  "$OUT_DIR" \
  --device "$DEVICE" \
  --android "$ANDROID_VERSION" \
  --app-version "$APP_VERSION"

rm -rf "$LATEST"
mkdir -p "$LATEST"
cp -a "$OUT_DIR"/. "$LATEST"/

log "Restaurando release sem apagar dados"
"${ADB[@]}" install -r "$RELEASE_APK" >/dev/null
RESTORE_RELEASE=0

echo
echo "COLETA ASR CONCLUÍDA"
echo "  Resultados: $OUT_DIR"
echo "  Último resultado: $LATEST"
echo "  Nenhum áudio, remetente, texto ou transcrição foi salvo."
echo
cat "$OUT_DIR/report.md"
