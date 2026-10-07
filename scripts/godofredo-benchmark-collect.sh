#!/usr/bin/env bash
set -Eeuo pipefail
APP_ID="br.com.wanotifkeeper.godofredo.benchmark"
OUT_BASE="diagnostics/godofredo-device-benchmark"
STAMP="$(date '+%Y-%m-%d-%H-%M-%S')"
OUT_DIR="$OUT_BASE/$STAMP"
LATEST="$OUT_BASE/latest"
SAFE_DIR="publisher/evidence/godofredo"
SAFE_FILE="$SAFE_DIR/$STAMP.json"
fail(){ printf 'ERRO: %s\n' "$*" >&2; exit 1; }
log(){ printf '==> %s\n' "$*"; }
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"
command -v adb >/dev/null 2>&1 || fail "adb não encontrado"
command -v python3 >/dev/null 2>&1 || fail "python3 não encontrado"
ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=( -s "$ANDROID_SERIAL" )
else
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  [[ ${#DEVICES[@]} -eq 1 ]] || fail "conecte exatamente um Android ou use ANDROID_SERIAL=<serial>"
  ADB+=( -s "${DEVICES[0]}" )
fi
"${ADB[@]}" shell pm path "$APP_ID" >/dev/null 2>&1 || fail "Godofredo Benchmark não está instalado"
mkdir -p "$OUT_DIR/raw" "$SAFE_DIR"
log "Coletando captura completa localmente"
"${ADB[@]}" exec-out run-as "$APP_ID" cat files/godofredo-benchmark/events.jsonl > "$OUT_DIR/raw/events.jsonl"
"${ADB[@]}" exec-out run-as "$APP_ID" cat files/godofredo-benchmark/status.json > "$OUT_DIR/status.json" 2>/dev/null || true
DEVICE="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
log "Gerando evidência sanitizada e versionável"
python3 - "$OUT_DIR/raw/events.jsonl" "$SAFE_FILE" "$DEVICE" "$SDK" <<'PY'
import json, sys, collections, statistics
src, dst, device, sdk = sys.argv[1:]
events=[]
for line in open(src, encoding="utf-8"):
    line=line.strip()
    if line:
        events.append(json.loads(line))
counts=collections.Counter(e.get("type","") for e in events)
sessions={}
errors=collections.Counter()
ready=[]
speech=[]
results=0
alt_counts=[]
ratings=collections.Counter()
rated_sessions=set()
wake_word_forms=collections.Counter()
for e in events:
    t=e.get("type")
    d=e.get("details") or {}
    sid=d.get("sessionId")
    if sid:
        sessions.setdefault(sid, []).append(e)
    if t=="recognition_error":
        errors[d.get("errorName","UNKNOWN")] += 1
    if t=="ready_for_speech" and isinstance(d.get("readyLatencyMs"), (int,float)) and d["readyLatencyMs"] >= 0:
        ready.append(d["readyLatencyMs"])
    if t=="beginning_of_speech" and isinstance(d.get("speechStartLatencyMs"), (int,float)) and d["speechStartLatencyMs"] >= 0:
        speech.append(d["speechStartLatencyMs"])
    if t=="final_results":
        results += 1
        alt_counts.append(len(d.get("alternatives") or []))
        alts=d.get("alternatives") or []
        if alts:
            first=(alts[0] or "").strip().lower()
            tokens=[token.strip(".,!?;:()[]{}") for token in first.split()]
            if "godofredo" in tokens:
                wake_word_forms["godofredo"] += 1
            elif "alfredo" in tokens:
                wake_word_forms["alfredo"] += 1
            elif "dodofredo" in tokens:
                wake_word_forms["dodofredo"] += 1
            else:
                wake_word_forms["outro"] += 1
    if t=="transcript_rating":
        rating=d.get("rating")
        if rating:
            ratings[rating] += 1
        if sid:
            rated_sessions.add(sid)
def stats(v):
    if not v: return None
    return {"count":len(v),"minMs":min(v),"medianMs":statistics.median(v),
            "maxMs":max(v),"meanMs":round(statistics.mean(v),1)}
out={
    "schema":1,
    "kind":"godofredo-benchmark-phase-1",
    "deviceModel":device,
    "androidSdk":int(sdk) if sdk.isdigit() else sdk,
    "eventCount":len(events),
    "sessionCount":len(sessions),
    "finalResultCount":results,
    "sessionSuccessRate":round(results/len(sessions),4) if sessions else None,
    "eventCounts":dict(sorted(counts.items())),
    "errorCounts":dict(sorted(errors.items())),
    "readyLatency":stats(ready),
    "speechStartLatency":stats(speech),
    "alternativesPerFinalResult":stats(alt_counts),
    "ratingCounts":dict(sorted(ratings.items())),
    "ratedSessionCount":len(rated_sessions),
    "ratingCoverage":round(len(rated_sessions)/results,4) if results else None,
    "wakeWordRecognitionCounts":dict(sorted(wake_word_forms.items())),
    "privacy":"raw transcripts and per-session transcript ratings remain local under diagnostics/**/raw and are gitignored; versioned evidence keeps aggregate counts only"
}
open(dst,"w",encoding="utf-8").write(json.dumps(out,ensure_ascii=False,indent=2)+"\n")
print(json.dumps(out,ensure_ascii=False,indent=2))
PY
rm -rf "$LATEST"
mkdir -p "$LATEST"
cp "$OUT_DIR/status.json" "$LATEST/" 2>/dev/null || true
cp "$SAFE_FILE" "$LATEST/evidence.json"
echo
echo "COLETA CONCLUÍDA"
echo "  Evidência versionável: $SAFE_FILE"
echo "  Captura bruta local: $OUT_DIR/raw/events.jsonl"
echo "  Último resumo: $LATEST/evidence.json"
echo
echo "Para versionar:"
echo "  git add '$SAFE_FILE' && git commit -m 'evidence: Godofredo benchmark $STAMP' && git push"
