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
session_rating={}
session_speech_duration={}
session_end_counts=collections.Counter()
session_last_partial_elapsed={}
session_first_end_elapsed={}
session_end_elapsed={}
session_final_elapsed={}
session_partial_after_first_end=collections.Counter()
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
    if t=="partial_results" and sid:
        elapsed=d.get("elapsedFromStartMs")
        if isinstance(elapsed, (int,float)) and elapsed >= 0:
            session_last_partial_elapsed[sid]=elapsed
            if sid in session_first_end_elapsed and elapsed > session_first_end_elapsed[sid]:
                session_partial_after_first_end[sid] += 1
    if t=="end_of_speech" and sid:
        session_end_counts[sid] += 1
        duration=d.get("detectedSpeechDurationMs")
        elapsed=d.get("elapsedFromStartMs")
        if isinstance(duration, (int,float)) and duration >= 0:
            session_speech_duration[sid]=duration
        if isinstance(elapsed, (int,float)) and elapsed >= 0:
            session_first_end_elapsed.setdefault(sid, elapsed)
            session_end_elapsed[sid]=elapsed
    if t=="final_results":
        results += 1
        if sid:
            elapsed=d.get("elapsedFromStartMs")
            if isinstance(elapsed, (int,float)) and elapsed >= 0:
                session_final_elapsed[sid]=elapsed
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
            if rating:
                session_rating[sid]=rating
def stats(v):
    if not v: return None
    return {"count":len(v),"minMs":min(v),"medianMs":statistics.median(v),
            "maxMs":max(v),"meanMs":round(statistics.mean(v),1)}
all_speech_durations=list(session_speech_duration.values())
incomplete_durations=[
    session_speech_duration[sid]
    for sid, rating in session_rating.items()
    if rating=="Incompleta" and sid in session_speech_duration
]
non_incomplete_durations=[
    session_speech_duration[sid]
    for sid, rating in session_rating.items()
    if rating!="Incompleta" and sid in session_speech_duration
]
last_partial_to_end={
    sid: session_end_elapsed[sid] - session_last_partial_elapsed[sid]
    for sid in session_end_elapsed
    if sid in session_last_partial_elapsed and session_end_elapsed[sid] >= session_last_partial_elapsed[sid]
}
incomplete_last_partial_to_end=[
    gap for sid, gap in last_partial_to_end.items()
    if session_rating.get(sid)=="Incompleta"
]
other_last_partial_to_end=[
    gap for sid, gap in last_partial_to_end.items()
    if session_rating.get(sid) not in (None, "Incompleta")
]
duplicate_end_sessions=sum(1 for count in session_end_counts.values() if count > 1)
max_end_callbacks=max(session_end_counts.values()) if session_end_counts else 0
partial_after_end_sessions=sum(1 for count in session_partial_after_first_end.values() if count > 0)
partial_after_end_total=sum(session_partial_after_first_end.values())
incomplete_partial_after_end=sum(
    session_partial_after_first_end.get(sid, 0)
    for sid, rating in session_rating.items()
    if rating=="Incompleta"
)
final_after_first_end=[
    session_final_elapsed[sid]-session_first_end_elapsed[sid]
    for sid in session_final_elapsed
    if sid in session_first_end_elapsed and session_final_elapsed[sid] >= session_first_end_elapsed[sid]
]
final_after_last_end=[
    session_final_elapsed[sid]-session_end_elapsed[sid]
    for sid in session_final_elapsed
    if sid in session_end_elapsed and session_final_elapsed[sid] >= session_end_elapsed[sid]
]
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
    "detectedSpeechDuration":stats(all_speech_durations),
    "detectedSpeechDurationIncomplete":stats(incomplete_durations),
    "detectedSpeechDurationOtherRatings":stats(non_incomplete_durations),
    "lastPartialToEndOfSpeech":stats(list(last_partial_to_end.values())),
    "lastPartialToEndOfSpeechIncomplete":stats(incomplete_last_partial_to_end),
    "lastPartialToEndOfSpeechOtherRatings":stats(other_last_partial_to_end),
    "sessionsWithDuplicateEndOfSpeech":duplicate_end_sessions,
    "maxEndOfSpeechCallbacksPerSession":max_end_callbacks,
    "sessionsWithPartialAfterFirstEndOfSpeech":partial_after_end_sessions,
    "partialResultsAfterFirstEndOfSpeech":partial_after_end_total,
    "partialResultsAfterFirstEndOfSpeechIncomplete":incomplete_partial_after_end,
    "finalResultAfterFirstEndOfSpeech":stats(final_after_first_end),
    "finalResultAfterLastEndOfSpeech":stats(final_after_last_end),
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
