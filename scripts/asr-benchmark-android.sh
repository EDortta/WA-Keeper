#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper"
MY_TRANSCRIVER="${MY_TRANSCRIVER:-/home/esteban/Sync/Projects/my-transcriver}"
POOL_SIZE="${ASR_SAMPLE_POOL:-24}"
ANDROID_MODELS="${ASR_ANDROID_MODELS:-tiny,base,small}"
WAIT_SECONDS="${ASR_WAIT_SECONDS:-900}"
OUT_BASE="${ASR_BENCHMARK_DIR:-diagnostics/asr-benchmark}"
STAMP="$(date '+%Y-%m-%d-%H-%M-%S')"
OUT_DIR="$OUT_BASE/$STAMP"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-asr.XXXXXX")"
CONSOLE_LOG="$WORK/console.log"
STAGE="inicialização"
DEVICE="desconhecido"

on_exit() {
  status=$?
  bash scripts/asr-publish-evidence.sh     "$status" "$STAGE" "$DEVICE" "$OUT_DIR" "$CONSOLE_LOG" "$ANDROID_MODELS" || true
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

for cmd in adb python3 ffprobe tar; do
  command -v "$cmd" >/dev/null 2>&1 || fail "$cmd não encontrado"
done
[[ -x ./gradlew ]] || fail "./gradlew não encontrado"
[[ -f "$MY_TRANSCRIVER/transcribe.py" ]] ||
  fail "transcribe.py não encontrado em $MY_TRANSCRIVER"

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
mkdir -p "$OUT_DIR"/{samples,reference,android,logs}

log "Android: ${DEVICE:-desconhecido}"
log "Relatório: $OUT_DIR"

STAGE="compilação da build debug"
log "Compilando build debug"
./gradlew --console=plain assembleDebug
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

STAGE="transcrição de referência no devel3"
log "Transcrevendo no devel3 com faster-whisper small local"
for sample_line in "${SAMPLE_LINES[@]}"; do
  IFS=

STAGE="transcrição local no Android"
IFS=',' read -r -a MODELS <<< "$ANDROID_MODELS"
for model in "${MODELS[@]}"; do
  model="$(echo "$model" | xargs)"
  [[ "$model" =~ ^(tiny|base|small)$ ]] || fail "modelo Android inválido: $model"
  mkdir -p "$OUT_DIR/android/$model"

  for sample_line in "${SAMPLE_LINES[@]}"; do
    IFS=

    "${ADB[@]}" push "$sample" "$remote_tmp" >/dev/null
    "${ADB[@]}" shell run-as "$APP_ID" mkdir -p files/asr-benchmark
    "${ADB[@]}" shell run-as "$APP_ID" cp "$remote_tmp" "files/asr-benchmark/$name"
    "${ADB[@]}" shell rm -f "$remote_tmp" >/dev/null 2>&1 || true
    "${ADB[@]}" shell run-as "$APP_ID" rm -f files/asr-benchmark/result.json

    log "Android: $model / $label / ${duration}s"
    "${ADB[@]}" shell am start -W \
      -n "$APP_ID/.AsrBenchmarkActivity" \
      --es input "$name" \
      --es model "$model" </dev/null >/dev/null

    deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while ! "${ADB[@]}" shell run-as "$APP_ID" test -s files/asr-benchmark/result.json >/dev/null 2>&1; do
      (( $(date +%s) < deadline )) || fail "timeout no Android: $model / $label"
      sleep 2
    done

    result="$OUT_DIR/android/$model/$label.json"
    "${ADB[@]}" exec-out run-as "$APP_ID" cat files/asr-benchmark/result.json > "$result"

    python3 - "$result" "$OUT_DIR/android/$model/$label.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
if not d.get("ok"):
    raise SystemExit("Falha no Android: " + str(d.get("error")))
open(sys.argv[2], "w", encoding="utf-8").write(d.get("text", "").strip() + "\n")
PY
  done
done

STAGE="cálculo de métricas e recomendação"
log "Calculando WER/CER e recomendação"
RECOMMENDED="$(python3 scripts/asr-benchmark-report.py report "$OUT_DIR" --device "$DEVICE" --models "$ANDROID_MODELS")"

log "Limpando modelos e áudios temporários do benchmark no celular"
"${ADB[@]}" shell run-as "$APP_ID" rm -rf files/asr-benchmark >/dev/null 2>&1 || true

STAGE="restauração da build release"
log "Reinstalando release sem apagar dados"
./gradlew --console=plain assembleRelease
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
"${ADB[@]}" install -r "$RELEASE_APK" >/dev/null ||
  fail "benchmark terminou, mas falhou ao reinstalar release; NÃO desinstale o app"

printf '\nBENCHMARK CONCLUÍDO\n'
printf 'Recomendação automática: sherpa-onnx Whisper %s\n' "$RECOMMENDED"
printf 'Relatório: %s/report.md\n' "$OUT_DIR"
printf 'Métricas: %s/metrics.json\n' "$OUT_DIR"
STAGE="concluído"
\t' read -r label id duration sender sample <<< "$sample_line"
  ref_dir="$OUT_DIR/reference/$label"
  mkdir -p "$ref_dir"
  result_json="$ref_dir/result.json"
  "$TRANSCRIVER_PY" scripts/asr-reference-local.py "$sample" \
    --model small \
    --device cpu \
    --compute-type int8 \
    --output "$result_json" \
    > "$OUT_DIR/logs/devel3-$label.log" 2>&1

  python3 - "$result_json" "$ref_dir/transcript.txt" "$ref_dir/elapsed-ms.txt" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if not data.get("ok"):
    raise SystemExit("Referência local falhou")
open(sys.argv[2], "w", encoding="utf-8").write(data.get("text", "").strip() + "\n")
open(sys.argv[3], "w", encoding="utf-8").write(str(data.get("elapsedMs", 0)) + "\n")
PY
done

STAGE="transcrição local no Android"
IFS=',' read -r -a MODELS <<< "$ANDROID_MODELS"
for model in "${MODELS[@]}"; do
  model="$(echo "$model" | xargs)"
  [[ "$model" =~ ^(tiny|base|small)$ ]] || fail "modelo Android inválido: $model"
  mkdir -p "$OUT_DIR/android/$model"

  while IFS=$'\t' read -r label id duration sender sample; do
    [[ "$label" == "classe" ]] && continue
    name="benchmark-${label}-${id}.opus"
    remote_tmp="/data/local/tmp/$name"

    "${ADB[@]}" push "$sample" "$remote_tmp" >/dev/null
    "${ADB[@]}" shell run-as "$APP_ID" mkdir -p files/asr-benchmark
    "${ADB[@]}" shell run-as "$APP_ID" cp "$remote_tmp" "files/asr-benchmark/$name"
    "${ADB[@]}" shell rm -f "$remote_tmp" >/dev/null 2>&1 || true
    "${ADB[@]}" shell run-as "$APP_ID" rm -f files/asr-benchmark/result.json

    log "Android: $model / $label / ${duration}s"
    "${ADB[@]}" shell am start -W \
      -n "$APP_ID/.AsrBenchmarkActivity" \
      --es input "$name" \
      --es model "$model" </dev/null >/dev/null

    deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while ! "${ADB[@]}" shell run-as "$APP_ID" test -s files/asr-benchmark/result.json >/dev/null 2>&1; do
      (( $(date +%s) < deadline )) || fail "timeout no Android: $model / $label"
      sleep 2
    done

    result="$OUT_DIR/android/$model/$label.json"
    "${ADB[@]}" exec-out run-as "$APP_ID" cat files/asr-benchmark/result.json > "$result"

    python3 - "$result" "$OUT_DIR/android/$model/$label.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
if not d.get("ok"):
    raise SystemExit("Falha no Android: " + str(d.get("error")))
open(sys.argv[2], "w", encoding="utf-8").write(d.get("text", "").strip() + "\n")
PY
  done < "$OUT_DIR/samples.tsv"
done

STAGE="cálculo de métricas e recomendação"
log "Calculando WER/CER e recomendação"
RECOMMENDED="$(python3 scripts/asr-benchmark-report.py report "$OUT_DIR" --device "$DEVICE" --models "$ANDROID_MODELS")"

log "Limpando modelos e áudios temporários do benchmark no celular"
"${ADB[@]}" shell run-as "$APP_ID" rm -rf files/asr-benchmark >/dev/null 2>&1 || true

STAGE="restauração da build release"
log "Reinstalando release sem apagar dados"
./gradlew --console=plain assembleRelease
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
"${ADB[@]}" install -r "$RELEASE_APK" >/dev/null ||
  fail "benchmark terminou, mas falhou ao reinstalar release; NÃO desinstale o app"

printf '\nBENCHMARK CONCLUÍDO\n'
printf 'Recomendação automática: sherpa-onnx Whisper %s\n' "$RECOMMENDED"
printf 'Relatório: %s/report.md\n' "$OUT_DIR"
printf 'Métricas: %s/metrics.json\n' "$OUT_DIR"
STAGE="concluído"
\t' read -r label id duration sender sample <<< "$sample_line"
    name="benchmark-${label}-${id}.opus"
    remote_tmp="/data/local/tmp/$name"

    "${ADB[@]}" push "$sample" "$remote_tmp" >/dev/null
    "${ADB[@]}" shell run-as "$APP_ID" mkdir -p files/asr-benchmark
    "${ADB[@]}" shell run-as "$APP_ID" cp "$remote_tmp" "files/asr-benchmark/$name"
    "${ADB[@]}" shell rm -f "$remote_tmp" >/dev/null 2>&1 || true
    "${ADB[@]}" shell run-as "$APP_ID" rm -f files/asr-benchmark/result.json

    log "Android: $model / $label / ${duration}s"
    "${ADB[@]}" shell am start -W \
      -n "$APP_ID/.AsrBenchmarkActivity" \
      --es input "$name" \
      --es model "$model" </dev/null >/dev/null

    deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while ! "${ADB[@]}" shell run-as "$APP_ID" test -s files/asr-benchmark/result.json >/dev/null 2>&1; do
      (( $(date +%s) < deadline )) || fail "timeout no Android: $model / $label"
      sleep 2
    done

    result="$OUT_DIR/android/$model/$label.json"
    "${ADB[@]}" exec-out run-as "$APP_ID" cat files/asr-benchmark/result.json > "$result"

    python3 - "$result" "$OUT_DIR/android/$model/$label.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
if not d.get("ok"):
    raise SystemExit("Falha no Android: " + str(d.get("error")))
open(sys.argv[2], "w", encoding="utf-8").write(d.get("text", "").strip() + "\n")
PY
  done < "$OUT_DIR/samples.tsv"
done

STAGE="cálculo de métricas e recomendação"
log "Calculando WER/CER e recomendação"
RECOMMENDED="$(python3 scripts/asr-benchmark-report.py report "$OUT_DIR" --device "$DEVICE" --models "$ANDROID_MODELS")"

log "Limpando modelos e áudios temporários do benchmark no celular"
"${ADB[@]}" shell run-as "$APP_ID" rm -rf files/asr-benchmark >/dev/null 2>&1 || true

STAGE="restauração da build release"
log "Reinstalando release sem apagar dados"
./gradlew --console=plain assembleRelease
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
"${ADB[@]}" install -r "$RELEASE_APK" >/dev/null ||
  fail "benchmark terminou, mas falhou ao reinstalar release; NÃO desinstale o app"

printf '\nBENCHMARK CONCLUÍDO\n'
printf 'Recomendação automática: sherpa-onnx Whisper %s\n' "$RECOMMENDED"
printf 'Relatório: %s/report.md\n' "$OUT_DIR"
printf 'Métricas: %s/metrics.json\n' "$OUT_DIR"
STAGE="concluído"
\t' read -r label id duration sender sample <<< "$sample_line"
  ref_dir="$OUT_DIR/reference/$label"
  mkdir -p "$ref_dir"
  result_json="$ref_dir/result.json"
  "$TRANSCRIVER_PY" scripts/asr-reference-local.py "$sample" \
    --model small \
    --device cpu \
    --compute-type int8 \
    --output "$result_json" \
    > "$OUT_DIR/logs/devel3-$label.log" 2>&1

  python3 - "$result_json" "$ref_dir/transcript.txt" "$ref_dir/elapsed-ms.txt" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if not data.get("ok"):
    raise SystemExit("Referência local falhou")
open(sys.argv[2], "w", encoding="utf-8").write(data.get("text", "").strip() + "\n")
open(sys.argv[3], "w", encoding="utf-8").write(str(data.get("elapsedMs", 0)) + "\n")
PY
done

STAGE="transcrição local no Android"
IFS=',' read -r -a MODELS <<< "$ANDROID_MODELS"
for model in "${MODELS[@]}"; do
  model="$(echo "$model" | xargs)"
  [[ "$model" =~ ^(tiny|base|small)$ ]] || fail "modelo Android inválido: $model"
  mkdir -p "$OUT_DIR/android/$model"

  while IFS=$'\t' read -r label id duration sender sample; do
    [[ "$label" == "classe" ]] && continue
    name="benchmark-${label}-${id}.opus"
    remote_tmp="/data/local/tmp/$name"

    "${ADB[@]}" push "$sample" "$remote_tmp" >/dev/null
    "${ADB[@]}" shell run-as "$APP_ID" mkdir -p files/asr-benchmark
    "${ADB[@]}" shell run-as "$APP_ID" cp "$remote_tmp" "files/asr-benchmark/$name"
    "${ADB[@]}" shell rm -f "$remote_tmp" >/dev/null 2>&1 || true
    "${ADB[@]}" shell run-as "$APP_ID" rm -f files/asr-benchmark/result.json

    log "Android: $model / $label / ${duration}s"
    "${ADB[@]}" shell am start -W \
      -n "$APP_ID/.AsrBenchmarkActivity" \
      --es input "$name" \
      --es model "$model" </dev/null >/dev/null

    deadline=$(( $(date +%s) + WAIT_SECONDS ))
    while ! "${ADB[@]}" shell run-as "$APP_ID" test -s files/asr-benchmark/result.json >/dev/null 2>&1; do
      (( $(date +%s) < deadline )) || fail "timeout no Android: $model / $label"
      sleep 2
    done

    result="$OUT_DIR/android/$model/$label.json"
    "${ADB[@]}" exec-out run-as "$APP_ID" cat files/asr-benchmark/result.json > "$result"

    python3 - "$result" "$OUT_DIR/android/$model/$label.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
if not d.get("ok"):
    raise SystemExit("Falha no Android: " + str(d.get("error")))
open(sys.argv[2], "w", encoding="utf-8").write(d.get("text", "").strip() + "\n")
PY
  done < "$OUT_DIR/samples.tsv"
done

STAGE="cálculo de métricas e recomendação"
log "Calculando WER/CER e recomendação"
RECOMMENDED="$(python3 scripts/asr-benchmark-report.py report "$OUT_DIR" --device "$DEVICE" --models "$ANDROID_MODELS")"

log "Limpando modelos e áudios temporários do benchmark no celular"
"${ADB[@]}" shell run-as "$APP_ID" rm -rf files/asr-benchmark >/dev/null 2>&1 || true

STAGE="restauração da build release"
log "Reinstalando release sem apagar dados"
./gradlew --console=plain assembleRelease
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
"${ADB[@]}" install -r "$RELEASE_APK" >/dev/null ||
  fail "benchmark terminou, mas falhou ao reinstalar release; NÃO desinstale o app"

printf '\nBENCHMARK CONCLUÍDO\n'
printf 'Recomendação automática: sherpa-onnx Whisper %s\n' "$RECOMMENDED"
printf 'Relatório: %s/report.md\n' "$OUT_DIR"
printf 'Métricas: %s/metrics.json\n' "$OUT_DIR"
STAGE="concluído"
