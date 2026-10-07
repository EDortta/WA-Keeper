#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper"
POOL_SIZE="${ASR_SAMPLE_POOL:-24}"
REPEATS="${ASR_THERMAL_REPEATS:-3}"
COOLDOWN="${ASR_COOLDOWN_SECONDS:-120}"
WAIT_SECONDS="${ASR_WAIT_SECONDS:-1800}"
OUT_BASE="${ASR_THERMAL_BENCHMARK_DIR:-diagnostics/asr-preprocess-benchmark}"
STAMP="$(date '+%Y-%m-%d-%H-%M-%S')-thermal"
OUT_DIR="$OUT_BASE/$STAMP"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-asr-thermal.XXXXXX")"
CONSOLE_LOG="$WORK/console.log"
STAGE="inicialização"
DEVICE="desconhecido"

on_exit() {
  status=$?
  bash scripts/asr-preprocess-publish-evidence.sh     "$status" "$STAGE" "$DEVICE" "$OUT_DIR" "$CONSOLE_LOG" || true
  rm -rf "$WORK"
  exit "$status"
}
trap on_exit EXIT

exec > >(tee -a "$CONSOLE_LOG") 2>&1

fail() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }
log() { printf '==> %s\n' "$*"; }

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"

for cmd in adb python3 ffmpeg ffprobe tar shuf; do
  command -v "$cmd" >/dev/null 2>&1 || fail "$cmd não encontrado"
done

ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=( -s "$ANDROID_SERIAL" )
else
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case "${#DEVICES[@]}" in
    0) fail "nenhum Android autorizado conectado" ;;
    1) ADB+=( -s "${DEVICES[0]}" ) ;;
    *) fail "mais de um Android conectado; use ANDROID_SERIAL=<serial>" ;;
  esac
fi

DEVICE="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
mkdir -p "$OUT_DIR"/{samples,variants,android,logs}

log "Android: $DEVICE"
log "Rodadas: $REPEATS | resfriamento entre execuções: ${COOLDOWN}s"

STAGE="compilação e instalação debug"
bash scripts/build.sh --lab --debug --skip-tests
"${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
"${ADB[@]}" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
"${ADB[@]}" shell run-as "$APP_ID" id >/dev/null 2>&1 || fail "run-as indisponível"

STAGE="seleção da amostra longa"
"${ADB[@]}" exec-out run-as "$APP_ID" tar -cf - databases > "$WORK/databases.tar"
tar -xf "$WORK/databases.tar" -C "$WORK"
DB="$WORK/databases/wanotif.db"

python3 scripts/asr-benchmark-report.py select "$DB" "$WORK/candidates.tsv" --pool "$POOL_SIZE"
: > "$WORK/measured.tsv"
while IFS=$'\t' read -r id sender text audio_path; do
  [[ -n "$audio_path" ]] || continue
  local_file="$WORK/audio-$id.opus"
  "${ADB[@]}" exec-out run-as "$APP_ID" cat "$audio_path" > "$local_file" 2>/dev/null || continue
  [[ -s "$local_file" ]] || continue
  duration="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$local_file" 2>/dev/null || true)"
  [[ "$duration" =~ ^[0-9]+([.][0-9]+)?$ ]] || continue
  printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$duration" "$sender" "$text" "$local_file" >> "$WORK/measured.tsv"
done < "$WORK/candidates.tsv"

python3 - "$WORK/measured.tsv" "$WORK/long.tsv" <<'PY'
import sys
rows=[]
for line in open(sys.argv[1], encoding="utf-8"):
    p=line.rstrip("\n").split("\t",4)
    if len(p)==5:
        rows.append((float(p[1]), p))
if not rows:
    raise SystemExit("nenhuma amostra encontrada")
target = 180.0
long_rows = [row for row in rows if row[0] >= 90.0]
pool = long_rows or rows
chosen = min(pool, key=lambda row: abs(row[0] - target))
open(sys.argv[2],"w",encoding="utf-8").write("\t".join(chosen[1])+"\n")
PY

IFS=$'\t' read -r id duration sender text source_file < "$WORK/long.tsv"
sample="$OUT_DIR/samples/longa-$id.opus"
cp "$source_file" "$sample"
printf 'classe\tid\tduracao_s\tremetente\tarquivo\nlonga\t%s\t%s\t%s\t%s\n' "$id" "$duration" "$sender" "$sample" > "$OUT_DIR/samples.tsv"
log "Amostra: ${duration}s | id=$id | $sender"

STAGE="geração das variantes"
V="$OUT_DIR/variants"
ffmpeg -hide_banner -loglevel error -y -i "$sample" -ac 1 -ar 16000 -c:a pcm_s16le "$V/original.wav"
ffmpeg -hide_banner -loglevel error -y -i "$sample" -af "silenceremove=start_periods=1:start_duration=0.15:start_threshold=-42dB:stop_periods=-1:stop_duration=0.45:stop_threshold=-42dB,atempo=1.15" -ac 1 -ar 16000 -c:a pcm_s16le "$V/silence_speed115.wav"

printf 'classe\tvariante\tduracao_s\tarquivo\n' > "$OUT_DIR/variants.tsv"
for variant in original silence_speed115; do
  d="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$V/$variant.wav")"
  printf 'longa\t%s\t%s\t%s\n' "$variant" "$d" "$V/$variant.wav" >> "$OUT_DIR/variants.tsv"
done

printf 'rodada\tordem\tvariante\n' > "$OUT_DIR/execution-order.tsv"
printf 'rodada\tordem\tvariante\tduracao_s\telapsed_ms\tresult_json\ttranscript\n' > "$OUT_DIR/runs.tsv"

STAGE="execuções aleatórias com resfriamento"
for round in $(seq 1 "$REPEATS"); do
  mapfile -t ORDER < <(printf '%s\n' original silence_speed115 | shuf)
  order_no=0

  for variant in "${ORDER[@]}"; do
    order_no=$((order_no + 1))
    printf '%s\t%s\t%s\n' "$round" "$order_no" "$variant" >> "$OUT_DIR/execution-order.tsv"

    if ! [[ "$round" == "1" && "$order_no" == "1" ]]; then
      log "Resfriando ${COOLDOWN}s"
      sleep "$COOLDOWN"
    fi

    local_file="$V/$variant.wav"
    transformed="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$local_file")"
    name="thermal-r${round}-${order_no}-${variant}.wav"
    remote_tmp="/data/local/tmp/$name"

    "${ADB[@]}" push "$local_file" "$remote_tmp" >/dev/null
    "${ADB[@]}" shell run-as "$APP_ID" mkdir -p files/asr-benchmark
    "${ADB[@]}" shell run-as "$APP_ID" cp "$remote_tmp" "files/asr-benchmark/$name"
    "${ADB[@]}" shell rm -f "$remote_tmp" >/dev/null 2>&1 || true
    "${ADB[@]}" shell run-as "$APP_ID" rm -f files/asr-benchmark/result.json

    log "Rodada $round | ordem $order_no | $variant | ${transformed}s"
    "${ADB[@]}" shell am start -W -n "$APP_ID/.AsrBenchmarkActivity" --es input "$name" --es model small </dev/null >/dev/null

    deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while ! "${ADB[@]}" shell run-as "$APP_ID" test -s files/asr-benchmark/result.json >/dev/null 2>&1; do
      (( $(date +%s) < deadline )) || fail "timeout: rodada $round / $variant"
      sleep 2
    done

    outdir="$OUT_DIR/android/round-$round"
    mkdir -p "$outdir"
    result="$outdir/$order_no-$variant.json"
    transcript="$outdir/$order_no-$variant.txt"
    "${ADB[@]}" exec-out run-as "$APP_ID" cat files/asr-benchmark/result.json > "$result"

    elapsed_ms="$(python3 - "$result" "$transcript" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
if not d.get("ok"):
    raise SystemExit("Falha no Android: "+str(d.get("error")))
open(sys.argv[2],"w",encoding="utf-8").write(d.get("text","").strip()+"\n")
print(d.get("elapsedMs",0))
PY
)"

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n'       "$round" "$order_no" "$variant" "$transformed" "$elapsed_ms" "$result" "$transcript" >> "$OUT_DIR/runs.tsv"
  done
done

STAGE="cálculo das métricas"
RECOMMENDED="$(python3 scripts/asr-thermal-report.py "$OUT_DIR" --device "$DEVICE")"

log "Limpando temporários do benchmark no celular"
"${ADB[@]}" shell run-as "$APP_ID" rm -rf files/asr-benchmark >/dev/null 2>&1 || true

STAGE="restauração da release"
bash scripts/build.sh --lab --release --skip-tests
"${ADB[@]}" install -r app/build/outputs/apk/release/app-release.apk >/dev/null ||
  fail "benchmark terminou, mas falhou ao reinstalar release"

printf '\nBENCHMARK TÉRMICO CONCLUÍDO\n'
printf 'Recomendação automática: %s\n' "$RECOMMENDED"
printf 'Relatório: %s/report.md\n' "$OUT_DIR"
STAGE="concluído"
