#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper.benchmark"
TARGET_SECONDS="${ASR_TARGET_SECONDS:-180}"
REPEATS="${ASR_THERMAL_REPEATS:-3}"
COOLDOWN="${ASR_COOLDOWN_SECONDS:-120}"

fail() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }
log() { printf '==> %s\n' "$*"; }

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"

for cmd in adb ffmpeg ffprobe python3; do
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

BACKUP_ROOT="${ASR_LOCAL_BACKUP_ROOT:-$ROOT/local-backup/$SERIAL}"
[[ -d "$BACKUP_ROOT" ]] || fail "backup local não encontrado: $BACKUP_ROOT"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-benchmark-install.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

log "Selecionando áudio real do WA-Keeper próximo de ${TARGET_SECONDS}s"
: > "$WORK/measured.tsv"
while IFS= read -r file; do
  [[ -s "$file" ]] || continue
  duration="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$file" 2>/dev/null || true)"
  [[ "$duration" =~ ^[0-9]+([.][0-9]+)?$ ]] || continue
  printf '%s\t%s\n' "$duration" "$file" >> "$WORK/measured.tsv"
done < <(
  find "$BACKUP_ROOT" -type f \
    \( -iname '*.opus' -o -iname '*.ogg' -o -iname '*.m4a' -o -iname '*.aac' -o -iname '*.mp3' -o -iname '*.wav' \) \
    2>/dev/null
)

[[ -s "$WORK/measured.tsv" ]] || fail "nenhum áudio utilizável encontrado em $BACKUP_ROOT"

python3 - "$WORK/measured.tsv" "$WORK/chosen.tsv" "$TARGET_SECONDS" <<'PY'
import sys
rows=[]
for line in open(sys.argv[1], encoding="utf-8"):
    duration, path = line.rstrip("\n").split("\t",1)
    rows.append((float(duration), path))
target=float(sys.argv[3])
eligible=[r for r in rows if r[0]>=90.0]
pool=eligible or rows
chosen=min(pool, key=lambda r: abs(r[0]-target))
open(sys.argv[2],"w",encoding="utf-8").write(f"{chosen[0]:.3f}\t{chosen[1]}\n")
PY

IFS=$'\t' read -r duration source < "$WORK/chosen.tsv"
log "Amostra: ${duration}s"
log "Origem: $source"

ffmpeg -hide_banner -loglevel error -y -i "$source" \
  -ac 1 -ar 16000 -c:a pcm_s16le "$WORK/original.wav"

ffmpeg -hide_banner -loglevel error -y -i "$source" \
  -af "silenceremove=start_periods=1:start_duration=0.15:start_threshold=-42dB:stop_periods=-1:stop_duration=0.45:stop_threshold=-42dB,atempo=1.15" \
  -ac 1 -ar 16000 -c:a pcm_s16le "$WORK/silence_speed115.wav"

log "Compilando aplicativo de benchmark"
./gradlew --console=plain :benchmark:assembleDebug
APK="benchmark/build/outputs/apk/debug/benchmark-debug.apk"
[[ -f "$APK" ]] || fail "APK não encontrado: $APK"

log "Instalando aplicativo de benchmark separado"
"${ADB[@]}" install -r "$APK" >/dev/null
"${ADB[@]}" shell pm clear "$APP_ID" >/dev/null || true
"${ADB[@]}" shell monkey -p "$APP_ID" 1 >/dev/null 2>&1 || true
sleep 1

"${ADB[@]}" shell run-as "$APP_ID" mkdir -p files/benchmark/input
for variant in original silence_speed115; do
  remote="/data/local/tmp/$variant.wav"
  "${ADB[@]}" push "$WORK/$variant.wav" "$remote" >/dev/null
  "${ADB[@]}" shell run-as "$APP_ID" cp "$remote" "files/benchmark/input/$variant.wav"
  "${ADB[@]}" shell rm -f "$remote" >/dev/null 2>&1 || true
done

printf '%s\n' "$(basename "$source")" > "$WORK/source-name.txt"
"${ADB[@]}" push "$WORK/source-name.txt" /data/local/tmp/source-name.txt >/dev/null
"${ADB[@]}" shell run-as "$APP_ID" cp /data/local/tmp/source-name.txt files/benchmark/source-name.txt
"${ADB[@]}" shell rm -f /data/local/tmp/source-name.txt >/dev/null 2>&1 || true

log "Verificando arquivos preparados dentro do app de benchmark"
"${ADB[@]}" shell run-as "$APP_ID" test -s files/benchmark/input/original.wav ||
  fail "original.wav não foi copiado para o aplicativo"
"${ADB[@]}" shell run-as "$APP_ID" test -s files/benchmark/input/silence_speed115.wav ||
  fail "silence_speed115.wav não foi copiado para o aplicativo"

log "Iniciando benchmark autônomo"
"${ADB[@]}" shell am start -W \
  -n "$APP_ID/.BenchmarkActivity" \
  --ez autonomous true \
  --ei repeats "$REPEATS" \
  --ei cooldownSeconds "$COOLDOWN" \
  --es model small >/dev/null

sleep 2
log "Estado inicial:"
"${ADB[@]}" exec-out run-as "$APP_ID" cat files/benchmark/status.json 2>/dev/null || true

echo
echo "PRONTO"
echo "  O benchmark continua no próprio celular."
echo "  Pode desconectar o cabo USB."
echo "  O WA-Keeper não foi substituído nem parado."
echo "  Depois reconecte e rode:"
echo "    bash scripts/asr-benchmark-collect-remove.sh"
