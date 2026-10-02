#!/usr/bin/env bash
set -Eeuo pipefail

BENCHMARK_APP_ID="br.com.wanotifkeeper.benchmark"
REPEATS="${ASR_THERMAL_REPEATS:-3}"
COOLDOWN="${ASR_COOLDOWN_SECONDS:-120}"
WAIT_SECONDS="${ASR_WAIT_SECONDS:-1800}"
TARGET_SECONDS="${ASR_TARGET_SECONDS:-180}"
OUT_BASE="${ASR_STANDALONE_BENCHMARK_DIR:-diagnostics/asr-preprocess-benchmark}"
STAMP="$(date '+%Y-%m-%d-%H-%M-%S')-standalone"
OUT_DIR="$OUT_BASE/$STAMP"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-asr-standalone.XXXXXX")"
CONSOLE_LOG="$WORK/console.log"
STAGE="inicialização"
DEVICE="desconhecido"

on_exit() {
  status=$?
  bash scripts/asr-preprocess-publish-evidence.sh "$status" "$STAGE" "$DEVICE" "$OUT_DIR" "$CONSOLE_LOG" || true
  rm -rf "$WORK"
  exit "$status"
}
trap on_exit EXIT

exec > >(tee -a "$CONSOLE_LOG") 2>&1

fail() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }
log() { printf '==> %s\n' "$*"; }

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"

for cmd in adb python3 ffmpeg ffprobe shuf; do
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

DEVICE="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
mkdir -p "$OUT_DIR"/{samples,variants,android,logs}

log "Android: $DEVICE"
log "Instalando somente o aplicativo de benchmark; WA-Keeper não será substituído nem parado"

STAGE="compilação do aplicativo de benchmark"
./gradlew --console=plain :benchmark:assembleDebug
APK="benchmark/build/outputs/apk/debug/benchmark-debug.apk"
[[ -f "$APK" ]] || fail "APK de benchmark não encontrado"

STAGE="instalação do aplicativo de benchmark"
"${ADB[@]}" install -r "$APK" >/dev/null
"${ADB[@]}" shell run-as "$BENCHMARK_APP_ID" id >/dev/null 2>&1 || fail "run-as indisponível no app de benchmark"

STAGE="seleção autônoma de áudio"
LOCAL_BACKUP_ROOT="${ASR_LOCAL_BACKUP_ROOT:-$ROOT/local-backup/$SERIAL}"
EXPLICIT_AUDIO="${ASR_BENCHMARK_AUDIO:-}"

: > "$WORK/measured.tsv"

measure_local_file() {
  local source="$1"
  [[ -f "$source" && -s "$source" ]] || return 0
  local duration
  duration="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$source" 2>/dev/null || true)"
  [[ "$duration" =~ ^[0-9]+([.][0-9]+)?$ ]] || return 0
  printf '%s\t%s\t%s\n' "$duration" "$source" "$source" >> "$WORK/measured.tsv"
}

if [[ -n "$EXPLICIT_AUDIO" ]]; then
  log "Usando áudio explícito: $EXPLICIT_AUDIO"
  measure_local_file "$EXPLICIT_AUDIO"
elif [[ -d "$LOCAL_BACKUP_ROOT" ]]; then
  log "Usando primeiro os áudios reais exportados pelo WA-Keeper"
  log "Fonte: $LOCAL_BACKUP_ROOT"
  while IFS= read -r source; do
    measure_local_file "$source"
  done < <(
    find "$LOCAL_BACKUP_ROOT" -type f \
      \( -iname '*.opus' -o -iname '*.ogg' -o -iname '*.m4a' -o -iname '*.aac' -o -iname '*.mp3' -o -iname '*.wav' \) \
      2>/dev/null
  )
fi

if [[ ! -s "$WORK/measured.tsv" ]]; then
  log "Backup local não forneceu áudio utilizável; procurando mídia pública no Android"
  "${ADB[@]}" shell sh -c 'find /sdcard/Android/media /sdcard/WhatsApp /sdcard/Download -type f 2>/dev/null | grep -Ei "\\.(opus|ogg|m4a|aac|mp3|wav)$"' \
    | tr -d '\r' | tail -n 200 > "$WORK/candidates.txt" || true

  idx=0
  while IFS= read -r remote; do
    [[ -n "$remote" ]] || continue
    idx=$((idx + 1))
    local_file="$WORK/candidate-$idx"
    "${ADB[@]}" exec-out sh -c "cat \"$remote\"" > "$local_file" 2>/dev/null || continue
    [[ -s "$local_file" ]] || continue
    duration="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$local_file" 2>/dev/null || true)"
    [[ "$duration" =~ ^[0-9]+([.][0-9]+)?$ ]] || continue
    printf '%s\t%s\t%s\n' "$duration" "$local_file" "$remote" >> "$WORK/measured.tsv"
  done < "$WORK/candidates.txt"
fi

[[ -s "$WORK/measured.tsv" ]] || fail "nenhum áudio real do WA-Keeper/WhatsApp pôde ser medido"
log "Áudios candidatos medidos: $(wc -l < "$WORK/measured.tsv")"

python3 - "$WORK/measured.tsv" "$WORK/chosen.tsv" "$TARGET_SECONDS" <<'PY'
import sys
rows=[]
for line in open(sys.argv[1], encoding="utf-8"):
    p=line.rstrip("\n").split("\t",2)
    if len(p)==3:
        rows.append((float(p[0]),p[1],p[2]))
target=float(sys.argv[3])
eligible=[r for r in rows if r[0]>=90.0]
pool=eligible or rows
chosen=min(pool,key=lambda r:abs(r[0]-target))
open(sys.argv[2],"w",encoding="utf-8").write(f"{chosen[0]:.3f}\t{chosen[1]}\t{chosen[2]}\n")
PY

IFS=$'\t' read -r duration source_file remote_path < "$WORK/chosen.tsv"
sample="$OUT_DIR/samples/longa.opus"
cp "$source_file" "$sample"
printf 'classe\tid\tduracao_s\tremetente\tarquivo\nlonga\tstandalone\t%s\tanonimo\t%s\n' "$duration" "$sample" > "$OUT_DIR/samples.tsv"
log "Amostra escolhida: ${duration}s"
log "Origem da amostra: $remote_path"
log "WA-Keeper continua instalado e operando normalmente durante todo o teste"

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

STAGE="execuções sob carga real"
for round in $(seq 1 "$REPEATS"); do
  mapfile -t ORDER < <(printf '%s\n' original silence_speed115 | shuf)
  order_no=0
  for variant in "${ORDER[@]}"; do
    order_no=$((order_no + 1))
    printf '%s\t%s\t%s\n' "$round" "$order_no" "$variant" >> "$OUT_DIR/execution-order.tsv"
    if ! [[ "$round" == "1" && "$order_no" == "1" ]]; then
      log "Aguardando ${COOLDOWN}s; o celular pode continuar sendo usado normalmente"
      sleep "$COOLDOWN"
    fi
    local_file="$V/$variant.wav"
    transformed="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$local_file")"
    name="run-r${round}-${order_no}-${variant}.wav"
    remote_tmp="/data/local/tmp/$name"
    "${ADB[@]}" push "$local_file" "$remote_tmp" >/dev/null
    "${ADB[@]}" shell run-as "$BENCHMARK_APP_ID" mkdir -p files/benchmark
    "${ADB[@]}" shell run-as "$BENCHMARK_APP_ID" cp "$remote_tmp" "files/benchmark/$name"
    "${ADB[@]}" shell rm -f "$remote_tmp" >/dev/null 2>&1 || true
    "${ADB[@]}" shell run-as "$BENCHMARK_APP_ID" rm -f files/benchmark/result.json
    log "Rodada $round | ordem $order_no | $variant | ${transformed}s"
    "${ADB[@]}" shell am start -W -n "$BENCHMARK_APP_ID/.BenchmarkActivity" --es input "$name" --es model small </dev/null >/dev/null
    deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while ! "${ADB[@]}" shell run-as "$BENCHMARK_APP_ID" test -s files/benchmark/result.json >/dev/null 2>&1; do
      (( $(date +%s) < deadline )) || fail "timeout: rodada $round / $variant"
      sleep 2
    done
    outdir="$OUT_DIR/android/round-$round"
    mkdir -p "$outdir"
    result="$outdir/$order_no-$variant.json"
    transcript="$outdir/$order_no-$variant.txt"
    "${ADB[@]}" exec-out run-as "$BENCHMARK_APP_ID" cat files/benchmark/result.json > "$result"
    elapsed_ms="$(python3 - "$result" "$transcript" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
if not d.get("ok"):
    raise SystemExit("Falha no benchmark Android: "+str(d.get("error")))
open(sys.argv[2],"w",encoding="utf-8").write(d.get("text","").strip()+"\n")
print(d.get("elapsedMs",0))
PY
)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$round" "$order_no" "$variant" "$transformed" "$elapsed_ms" "$result" "$transcript" >> "$OUT_DIR/runs.tsv"
  done
done

STAGE="cálculo das métricas"
python3 scripts/asr-thermal-report.py "$OUT_DIR" --device "$DEVICE" >/dev/null
log "Limpando somente arquivos temporários do aplicativo de benchmark"
"${ADB[@]}" shell run-as "$BENCHMARK_APP_ID" rm -rf files/benchmark >/dev/null 2>&1 || true
printf '\nBENCHMARK STANDALONE CONCLUÍDO\n'
printf 'WA-Keeper não foi substituído nem parado.\n'
printf 'Relatório: %s/report.md\n' "$OUT_DIR"
STAGE="concluído"
