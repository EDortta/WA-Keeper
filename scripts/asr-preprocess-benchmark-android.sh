#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper"
MY_TRANSCRIVER="${MY_TRANSCRIVER:-/home/esteban/Sync/Projects/my-transcriver}"
POOL_SIZE="${ASR_SAMPLE_POOL:-24}"
WAIT_SECONDS="${ASR_WAIT_SECONDS:-1200}"
OUT_BASE="${ASR_PREPROCESS_BENCHMARK_DIR:-diagnostics/asr-preprocess-benchmark}"
STAMP="$(date '+%Y-%m-%d-%H-%M-%S')"
OUT_DIR="$OUT_BASE/$STAMP"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-asr-pre.XXXXXX")"
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

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" ||
  fail "execute dentro do repositório WA-Keeper"
cd "$REPO_ROOT"

for cmd in adb python3 ffmpeg ffprobe tar; do
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
mkdir -p "$OUT_DIR"/{samples,reference,variants,android,logs}

log "Android: ${DEVICE:-desconhecido}"
log "Relatório: $OUT_DIR"

STAGE="compilação da build debug"
log "Compilando build debug"
bash scripts/build.sh --lab --debug --skip-tests
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$DEBUG_APK" ]] || fail "APK debug não encontrado"

STAGE="instalação da build debug"
log "Instalando build debug sem apagar dados"
"${ADB[@]}" install -r "$DEBUG_APK" ||
  fail "não consegui instalar debug por cima da versão atual"
"${ADB[@]}" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
"${ADB[@]}" shell run-as "$APP_ID" id >/dev/null 2>&1 ||
  fail "run-as não ficou disponível"

STAGE="coleta do banco e seleção de amostras"
log "Lendo banco do WA-Keeper instalado"
"${ADB[@]}" exec-out run-as "$APP_ID" tar -cf - databases > "$WORK/databases.tar"
tar -xf "$WORK/databases.tar" -C "$WORK"
DB="$WORK/databases/wanotif.db"
[[ -f "$DB" ]] || fail "wanotif.db não encontrado"

python3 scripts/asr-benchmark-report.py select "$DB" "$WORK/candidates.tsv" --pool "$POOL_SIZE"

log "Medindo duração de amostras aleatórias"
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

python3 scripts/asr-benchmark-report.py pick "$WORK/measured.tsv" "$WORK/selected.tsv"

printf 'classe\tid\tduracao_s\tremetente\tarquivo\n' > "$OUT_DIR/samples.tsv"
while IFS=$'\t' read -r label id duration sender text source_file; do
  sample="$OUT_DIR/samples/${label}-${id}.opus"
  cp "$source_file" "$sample"
  printf '%s\t%s\t%s\t%s\t%s\n' "$label" "$id" "$duration" "$sender" "$sample" >> "$OUT_DIR/samples.tsv"
done < "$WORK/selected.tsv"

log "Três amostras escolhidas"
tail -n +2 "$OUT_DIR/samples.tsv" | awk -F '\t' '{printf "  %-8s %6.1fs  id=%s  %s\n", $1, $3, $2, $4}'

if [[ -x "$MY_TRANSCRIVER/.venv/bin/python" ]]; then
  TRANSCRIVER_PY="$MY_TRANSCRIVER/.venv/bin/python"
elif [[ -x "$MY_TRANSCRIVER/.venv/bin/python3" ]]; then
  TRANSCRIVER_PY="$MY_TRANSCRIVER/.venv/bin/python3"
else
  TRANSCRIVER_PY="python3"
fi

"$TRANSCRIVER_PY" -c 'import faster_whisper' >/dev/null 2>&1 ||
  fail "faster-whisper não está disponível no Python escolhido: $TRANSCRIVER_PY"

mapfile -t SAMPLE_LINES < <(tail -n +2 "$OUT_DIR/samples.tsv")
TAB="$(printf '\t')"

STAGE="transcrição de referência no devel3"
log "Criando referência com faster-whisper small"
for sample_line in "${SAMPLE_LINES[@]}"; do
  IFS="$TAB" read -r label id duration sender sample <<< "$sample_line"
  ref_dir="$OUT_DIR/reference/$label"
  mkdir -p "$ref_dir"
  "$TRANSCRIVER_PY" scripts/asr-reference-local.py "$sample"     --model small     --device cpu     --compute-type int8     --output "$ref_dir/result.json"     > "$OUT_DIR/logs/devel3-$label.log" 2>&1

  python3 - "$ref_dir/result.json" "$ref_dir/transcript.txt" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
open(sys.argv[2], "w", encoding="utf-8").write(data.get("text", "").strip() + "\n")
PY
done

STAGE="geração das variantes"
log "Gerando variantes de pré-processamento"
printf 'classe\tvariante\tduracao_s\tarquivo\n' > "$OUT_DIR/variants.tsv"

for sample_line in "${SAMPLE_LINES[@]}"; do
  IFS="$TAB" read -r label id original_duration sender sample <<< "$sample_line"
  variant_dir="$OUT_DIR/variants/$label"
  mkdir -p "$variant_dir"

  ffmpeg -hide_banner -loglevel error -y -i "$sample"     -ac 1 -ar 16000 -c:a pcm_s16le "$variant_dir/original.wav"

  ffmpeg -hide_banner -loglevel error -y -i "$sample"     -af "silenceremove=start_periods=1:start_duration=0.15:start_threshold=-42dB:stop_periods=-1:stop_duration=0.45:stop_threshold=-42dB"     -ac 1 -ar 16000 -c:a pcm_s16le "$variant_dir/silence.wav"

  ffmpeg -hide_banner -loglevel error -y -i "$sample"     -af "atempo=1.15"     -ac 1 -ar 16000 -c:a pcm_s16le "$variant_dir/speed115.wav"

  ffmpeg -hide_banner -loglevel error -y -i "$sample"     -af "atempo=1.25"     -ac 1 -ar 16000 -c:a pcm_s16le "$variant_dir/speed125.wav"

  ffmpeg -hide_banner -loglevel error -y -i "$sample"     -af "silenceremove=start_periods=1:start_duration=0.15:start_threshold=-42dB:stop_periods=-1:stop_duration=0.45:stop_threshold=-42dB,atempo=1.15"     -ac 1 -ar 16000 -c:a pcm_s16le "$variant_dir/silence_speed115.wav"

  for variant in original silence speed115 speed125 silence_speed115; do
    file="$variant_dir/$variant.wav"
    duration="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$file")"
    python3 - "$variant_dir/$variant.json" "$duration" "$original_duration" "$variant" <<'PY'
import json, sys
path, duration, original, variant = sys.argv[1:]
with open(path, "w", encoding="utf-8") as f:
    json.dump({
        "variant": variant,
        "duration": float(duration),
        "originalDuration": float(original),
    }, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY
    printf '%s\t%s\t%s\t%s\n' "$label" "$variant" "$duration" "$file" >> "$OUT_DIR/variants.tsv"
  done
done

STAGE="transcrição local no Android"
log "Transcrevendo variantes no Android com Whisper small"
for variant in original silence speed115 speed125 silence_speed115; do
  mkdir -p "$OUT_DIR/android/$variant"

  for sample_line in "${SAMPLE_LINES[@]}"; do
    IFS="$TAB" read -r label id original_duration sender sample <<< "$sample_line"
    local_variant="$OUT_DIR/variants/$label/$variant.wav"
    name="pre-${variant}-${label}-${id}.wav"
    remote_tmp="/data/local/tmp/$name"

    "${ADB[@]}" push "$local_variant" "$remote_tmp" >/dev/null
    "${ADB[@]}" shell run-as "$APP_ID" mkdir -p files/asr-benchmark
    "${ADB[@]}" shell run-as "$APP_ID" cp "$remote_tmp" "files/asr-benchmark/$name"
    "${ADB[@]}" shell rm -f "$remote_tmp" >/dev/null 2>&1 || true
    "${ADB[@]}" shell run-as "$APP_ID" rm -f files/asr-benchmark/result.json

    transformed="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["duration"])' "$OUT_DIR/variants/$label/$variant.json")"
    log "Android: $variant / $label / ${transformed}s"

    "${ADB[@]}" shell am start -W       -n "$APP_ID/.AsrBenchmarkActivity"       --es input "$name"       --es model small </dev/null >/dev/null

    deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while ! "${ADB[@]}" shell run-as "$APP_ID" test -s files/asr-benchmark/result.json >/dev/null 2>&1; do
      (( $(date +%s) < deadline )) || fail "timeout no Android: $variant / $label"
      sleep 2
    done

    result="$OUT_DIR/android/$variant/$label.json"
    "${ADB[@]}" exec-out run-as "$APP_ID" cat files/asr-benchmark/result.json > "$result"

    python3 - "$result" "$OUT_DIR/android/$variant/$label.txt" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if not data.get("ok"):
    raise SystemExit("Falha no Android: " + str(data.get("error")))
open(sys.argv[2], "w", encoding="utf-8").write(data.get("text", "").strip() + "\n")
PY
  done
done

STAGE="cálculo de métricas e recomendação"
log "Calculando impacto de qualidade e tempo"
RECOMMENDED="$(python3 scripts/asr-preprocess-report.py "$OUT_DIR" --device "$DEVICE")"

log "Limpando arquivos temporários do benchmark no celular"
"${ADB[@]}" shell run-as "$APP_ID" rm -rf files/asr-benchmark >/dev/null 2>&1 || true

STAGE="restauração da build release"
log "Reinstalando release sem apagar dados"
bash scripts/build.sh --lab --release --skip-tests
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
"${ADB[@]}" install -r "$RELEASE_APK" >/dev/null ||
  fail "benchmark terminou, mas falhou ao reinstalar release; NÃO desinstale o app"

printf '\nBENCHMARK DE PRÉ-PROCESSAMENTO CONCLUÍDO\n'
printf 'Recomendação automática: %s\n' "$RECOMMENDED"
printf 'Relatório: %s/report.md\n' "$OUT_DIR"
printf 'Métricas: %s/metrics.json\n' "$OUT_DIR"
STAGE="concluído"
