#!/usr/bin/env bash
set -Eeuo pipefail

# WA Keeper - diagnóstico de mensagens agendadas via ADB
#
# Uso:
#   ./diagnose-wa-keeper-scheduled.sh
#   ./diagnose-wa-keeper-scheduled.sh com.seu.pacote
#   ./diagnose-wa-keeper-scheduled.sh com.seu.pacote "Nanda"
#
# Opcional:
#   SINCE="2026-09-18 00:00:00" ./diagnose-wa-keeper-scheduled.sh
#
# O script NÃO altera dados do aparelho. Apenas coleta evidências.

APP_HINT="${1:-}"
CONTACT_HINT="${2:-Nanda}"
SINCE="${SINCE:-$(date '+%Y-%m-%d 00:00:00')}"
STAMP="$(date '+%Y%m%d-%H%M%S')"
OUT="wa-keeper-diagnose-${STAMP}"

mkdir -p "$OUT"

log() {
  printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$OUT/summary.txt"
}

run() {
  local name="$1"
  shift
  {
    echo "\$ $*"
    "$@"
  } >"$OUT/$name.txt" 2>&1 || true
}

adb_shell() {
  adb shell "$@"
}

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERRO: '$1' não encontrado no PATH." >&2
    exit 1
  }
}

require adb

log "Verificando ADB..."
adb start-server >/dev/null 2>&1 || true

STATE="$(adb get-state 2>/dev/null || true)"
if [[ "$STATE" != "device" ]]; then
  echo "ERRO: nenhum Android conectado/autorizado via adb." >&2
  adb devices -l >&2 || true
  exit 2
fi

run adb_devices adb devices -l
run android_props adb shell getprop
run date_time adb shell 'echo "date=$(date)"; echo "epoch=$(date +%s)"; getprop persist.sys.timezone; settings get global auto_time; settings get global auto_time_zone'
run battery adb shell dumpsys battery
run power adb shell dumpsys power
run deviceidle adb shell dumpsys deviceidle
run connectivity adb shell dumpsys connectivity

PACKAGE="$APP_HINT"

if [[ -z "$PACKAGE" ]]; then
  mapfile -t CANDIDATES < <(
    adb shell pm list packages 2>/dev/null \
      | sed 's/^package://' \
      | tr -d '\r' \
      | grep -Ei 'wa.?keeper|keeper|whatsapp|wkeeper' \
      || true
  )

  for p in "${CANDIDATES[@]:-}"; do
    if [[ "$p" =~ [Kk]eeper ]] && [[ ! "$p" =~ whatsapp ]]; then
      PACKAGE="$p"
      break
    fi
  done

  if [[ -z "$PACKAGE" && ${#CANDIDATES[@]} -eq 1 ]]; then
    PACKAGE="${CANDIDATES[0]}"
  fi

  if [[ -z "$PACKAGE" ]]; then
    log "Não consegui identificar automaticamente o package do WA Keeper."
    log "Pacotes candidatos:"
    printf '%s\n' "${CANDIDATES[@]:-<nenhum>}" | tee "$OUT/package_candidates.txt"
    log "Rode novamente: $0 com.seu.package \"$CONTACT_HINT\""
    exit 3
  fi
fi

PACKAGE="$(echo "$PACKAGE" | tr -d '\r')"
log "Package detectado: $PACKAGE"
log "Contato/filtro: $CONTACT_HINT"
log "Logs desde: $SINCE"

run package_info adb shell dumpsys package "$PACKAGE"
run package_path adb shell pm path "$PACKAGE"
run package_uid adb shell "dumpsys package '$PACKAGE' | grep -E 'userId=|appId=|versionName=|versionCode='"
run processes adb shell ps -A
run app_process adb shell "ps -A | grep -F '$PACKAGE' || true"
run activity adb shell dumpsys activity processes
run activity_services adb shell dumpsys activity services "$PACKAGE"
run activity_broadcasts adb shell dumpsys activity broadcasts
run notifications adb shell dumpsys notification
run appops adb shell appops get "$PACKAGE"
run permissions adb shell dumpsys package "$PACKAGE"

run battery_optimization adb shell "
  echo '--- whitelist ---'
  dumpsys deviceidle whitelist
  echo
  echo '--- app standby bucket ---'
  am get-standby-bucket '$PACKAGE'
  echo
  echo '--- background restrictions ---'
  cmd appops get '$PACKAGE' RUN_IN_BACKGROUND 2>/dev/null || true
  cmd appops get '$PACKAGE' RUN_ANY_IN_BACKGROUND 2>/dev/null || true
  echo
  echo '--- exact alarm capability ---'
  cmd appops get '$PACKAGE' SCHEDULE_EXACT_ALARM 2>/dev/null || true
"

run alarms_all adb shell dumpsys alarm
grep -i -C 15 "$PACKAGE" "$OUT/alarms_all.txt" >"$OUT/alarms_app.txt" || true

run jobs_all adb shell dumpsys jobscheduler
grep -i -C 25 "$PACKAGE" "$OUT/jobs_all.txt" >"$OUT/jobs_app.txt" || true

run dumpsys_scheduler grep -Eis "$PACKAGE|workmanager|workspec|alarm|job|schedule" "$OUT/alarms_all.txt" "$OUT/jobs_all.txt"

run whatsapp_packages adb shell pm list packages
run whatsapp_process adb shell "ps -A | grep -Ei 'whatsapp|com\\.wa' || true"
run whatsapp_package_info adb shell '
  for p in $(pm list packages | sed "s/package://" | grep -Ei "whatsapp|com\\.wa" | head -10); do
    echo "===== $p ====="
    dumpsys package "$p" | grep -E "versionName=|versionCode=|enabled=|stopped=" | head -30
  done
'

log "Coletando logcat..."
if adb logcat -d -v threadtime -T "$SINCE" >"$OUT/logcat-full.txt" 2>"$OUT/logcat-error.txt"; then
  :
else
  adb logcat -d -v threadtime >"$OUT/logcat-full.txt" 2>&1 || true
fi

grep -Eis \
  "$PACKAGE|wa.?keeper|workmanager|workspec|worker|scheduler|schedule|alarmmanager|jobscheduler|foreground|background|doze|battery|exact.alarm|notification|whatsapp|send|message|exception|error|fatal|denied|securityexception" \
  "$OUT/logcat-full.txt" >"$OUT/logcat-relevant.txt" || true

if [[ -n "$CONTACT_HINT" ]]; then
  # Evidência específica do contato, separada de ruído sistêmico.
  grep -Fi -C 8 "$CONTACT_HINT" "$OUT/logcat-full.txt" >"$OUT/logcat-contact.txt" || true
  grep -Ei "WAK-Scheduled(Msg|Alarm).*$CONTACT_HINT|$CONTACT_HINT.*WAK-Scheduled(Msg|Alarm)"     "$OUT/logcat-full.txt" >"$OUT/logcat-scheduled-contact.txt" || true
fi

# Logs emitidos pelo mecanismo de agendamento, independentemente do contato.
grep -E "WAK-ScheduledMsg|WAK-ScheduledAlarm"   "$OUT/logcat-full.txt" >"$OUT/logcat-scheduled.txt" || true

run crashes adb shell dumpsys dropbox --print
grep -Eis -C 8 "$PACKAGE|FATAL EXCEPTION|ANR|SecurityException|IllegalStateException|ForegroundServiceStartNotAllowedException|BackgroundServiceStartNotAllowedException" \
  "$OUT/crashes.txt" >"$OUT/crashes-relevant.txt" || true

RUN_AS_OK=0
if adb shell "run-as '$PACKAGE' id" >/dev/null 2>&1; then
  RUN_AS_OK=1
  log "run-as disponível: vou inspecionar arquivos internos sem modificá-los."

  adb shell "run-as '$PACKAGE' sh -c 'pwd; find . -maxdepth 4 -type f -print 2>/dev/null'" \
    >"$OUT/private-files.txt" 2>&1 || true

  adb shell "run-as '$PACKAGE' sh -c 'find . -type f \( -name \"*.db\" -o -name \"*.sqlite\" -o -name \"*.sqlite3\" -o -name \"*.xml\" -o -name \"*.json\" \) -print 2>/dev/null'" \
    >"$OUT/private-data-files.txt" 2>&1 || true

  mkdir -p "$OUT/private"

  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    safe="$(echo "$f" | sed 's#^\./##; s#[/ ]#_#g')"

    if [[ "$f" =~ \.(db|sqlite|sqlite3)$ ]]; then
      adb exec-out run-as "$PACKAGE" cat "$f" >"$OUT/private/$safe" 2>/dev/null || true
      adb exec-out run-as "$PACKAGE" cat "${f}-wal" >"$OUT/private/${safe}-wal" 2>/dev/null || true
      adb exec-out run-as "$PACKAGE" cat "${f}-shm" >"$OUT/private/${safe}-shm" 2>/dev/null || true
    elif [[ "$f" =~ \.(xml|json)$ ]]; then
      adb exec-out run-as "$PACKAGE" cat "$f" >"$OUT/private/$safe" 2>/dev/null || true
    fi
  done <"$OUT/private-data-files.txt"

  if command -v sqlite3 >/dev/null 2>&1; then
    {
      for db in "$OUT/private/"*.db "$OUT/private/"*.sqlite "$OUT/private/"*.sqlite3; do
        [[ -f "$db" ]] || continue
        echo "===== DB: $db ====="
        sqlite3 "$db" ".tables" 2>&1 || true
        sqlite3 "$db" ".schema" 2>&1 || true
        echo
      done
    } >"$OUT/sqlite-schema.txt" 2>&1 || true

    python3 - "$OUT/private" "$OUT/sqlite-data.txt" "$CONTACT_HINT" <<'PY' || true
import os, sqlite3, sys, re
root, outfile, contact = sys.argv[1:4]
interesting = re.compile(r'(sched|message|msg|queue|outbox|send|work|task|job|contact|whats|pending)', re.I)

with open(outfile, 'w', encoding='utf-8', errors='replace') as out:
    for name in os.listdir(root):
        path = os.path.join(root, name)
        if not os.path.isfile(path):
            continue
        if not re.search(r'\.(db|sqlite|sqlite3)$', name, re.I):
            continue
        try:
            con = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
            tables = [r[0] for r in con.execute(
                "select name from sqlite_master where type='table' order by name"
            )]
            for table in tables:
                if not interesting.search(table):
                    continue
                out.write(f"\n===== {name} :: {table} =====\n")
                try:
                    cols = [r[1] for r in con.execute(f'pragma table_info("{table}")')]
                    out.write("COLUMNS: " + ", ".join(cols) + "\n")
                    rows = con.execute(f'SELECT * FROM "{table}" LIMIT 200').fetchall()
                    for row in rows:
                        line = repr(row)
                        if not contact or contact.lower() in line.lower() or interesting.search(line):
                            out.write(line + "\n")
                except Exception as e:
                    out.write(f"ERROR: {e}\n")
            con.close()
        except Exception as e:
            out.write(f"\n===== {name} ERROR: {e} =====\n")
PY
  fi
else
  log "run-as indisponível: APK provavelmente não é debuggable. Banco interno não pôde ser lido."
fi

{
  echo "WA KEEPER - RESUMO AUTOMÁTICO"
  echo "Gerado: $(date)"
  echo "Package: $PACKAGE"
  echo "Filtro contato: $CONTACT_HINT"
  echo
  echo "=== SINAIS IMPORTANTES ==="

  check() {
    local label="$1"
    local pattern="$2"
    shift 2
    if grep -Eis "$pattern" "$@" >/dev/null 2>&1; then
      echo "[ACHADO] $label"
      grep -Eis -m 8 "$pattern" "$@" 2>/dev/null | sed 's/^/    /'
    else
      echo "[não visto] $label"
    fi
    echo
  }

  check "Exceções/crashes" \
    'FATAL EXCEPTION|ANR|SecurityException|IllegalStateException|Exception|Error:' \
    "$OUT/logcat-relevant.txt" "$OUT/crashes-relevant.txt"

  check "Bloqueio por execução em background" \
    'BackgroundServiceStartNotAllowed|ForegroundServiceStartNotAllowed|background.*restricted|RUN_IN_BACKGROUND.*deny|RUN_ANY_IN_BACKGROUND.*deny' \
    "$OUT/logcat-relevant.txt" "$OUT/battery_optimization.txt"

  check "Problema com alarme exato" \
    'SCHEDULE_EXACT_ALARM|USE_EXACT_ALARM|exact alarm.*denied|exact.*alarm.*permission|fallback inexato' \
    "$OUT/logcat-relevant.txt" "$OUT/logcat-scheduled.txt" "$OUT/package_info.txt" "$OUT/appops.txt" "$OUT/battery_optimization.txt"

  check "WorkManager/Worker falhou ou foi cancelado" \
    'Worker.*FAIL|Worker.*failed|Work.*FAILED|CANCELLED|cancelled|retry|Result.failure|ExecutionException' \
    "$OUT/logcat-relevant.txt"

  check "Job/alarme do WA Keeper existe" \
    "$PACKAGE" \
    "$OUT/alarms_app.txt" "$OUT/jobs_app.txt"

  check "Doze/App Standby pode ter interferido" \
    'mState=IDLE|mState=IDLE_MAINTENANCE|standby|restricted|doze' \
    "$OUT/deviceidle.txt" "$OUT/battery_optimization.txt"

  if [[ -s "$OUT/logcat-scheduled-contact.txt" ]]; then
    echo "[ACHADO] '$CONTACT_HINT' apareceu em log específico do agendador."
    head -20 "$OUT/logcat-scheduled-contact.txt" | sed 's/^/    /'
  elif [[ -s "$OUT/logcat-contact.txt" ]]; then
    echo "[ACHADO PARCIAL] '$CONTACT_HINT' apareceu no logcat, mas não em log específico do agendador."
  else
    echo "[não visto] '$CONTACT_HINT' não apareceu no logcat."
  fi

  echo
  echo "run-as disponível: $RUN_AS_OK"
  echo
  echo "Arquivos prioritários para revisar:"
  echo "  1. diagnosis.txt"
  echo "  2. logcat-contact.txt"
  echo "  3. logcat-scheduled-contact.txt"
  echo "  4. logcat-scheduled.txt"
  echo "  5. logcat-relevant.txt"
  echo "  6. alarms_app.txt"
  echo "  7. jobs_app.txt"
  echo "  8. battery_optimization.txt"
  echo "  9. sqlite-data.txt (se existir)"
} >"$OUT/diagnosis.txt"

log "Diagnóstico concluído."
log "Abra primeiro: $OUT/diagnosis.txt"
log "Depois, se necessário: $OUT/logcat-contact.txt e $OUT/logcat-relevant.txt"

echo
cat "$OUT/diagnosis.txt"

echo
echo "Pasta completa de evidências: $OUT"
