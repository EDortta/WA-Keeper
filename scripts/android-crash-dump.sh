#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper"
ACTIVITY="$APP_ID/.MainActivity"
DIAGNOSTICS_BRANCH="diagnostics/android-deploy"
DIAGNOSTICS_FILE="diagnostics/android-deploy/last-runtime-crash.md"

fail() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$REPO_ROOT"
command -v adb >/dev/null 2>&1 || fail "adb não encontrado no PATH"

ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=( -s "$ANDROID_SERIAL" )
else
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case "${#DEVICES[@]}" in
    0) fail "nenhum Android autorizado conectado via adb" ;;
    1) ADB+=( -s "${DEVICES[0]}" ) ;;
    *) fail "há mais de um Android. Rode com ANDROID_SERIAL=<serial>." ;;
  esac
fi

DEVICE="$(${ADB[@]} shell getprop ro.product.model | tr -d '\r')"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-runtime.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT
RAW_LOG="$TMP_ROOT/runtime.log"
SAFE_LOG="$TMP_ROOT/runtime-safe.log"
DIAG_WORKTREE="$TMP_ROOT/repo"

printf '==> Limpando somente o logcat (não toca em dados do app)\n'
"${ADB[@]}" logcat -c || true
"${ADB[@]}" shell am force-stop "$APP_ID" || true
printf '==> Abrindo WA-Keeper\n'
"${ADB[@]}" shell am start -n "$ACTIVITY" >/dev/null 2>&1 || true
sleep 3

PID="$(${ADB[@]} shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
{
  printf 'WA-Keeper runtime diagnostic\n'
  printf 'Device: %s\n' "${DEVICE:-desconhecido}"
  printf 'Process alive after 3s: %s\n\n' "${PID:-NO}"
  printf '%s\n' '--- crash buffer ---'
  "${ADB[@]}" logcat -d -b crash 2>/dev/null || true
  printf '\n%s\n' '--- relevant main/system lines ---'
  "${ADB[@]}" logcat -d 2>/dev/null \
    | grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $APP_ID|$APP_ID|ActivityTaskManager|ActivityManager" \
    | tail -n 500 || true
} > "$RAW_LOG"

sed "s#${HOME:-/home/unknown}#~#g" "$RAW_LOG" > "$SAFE_LOG" || cp "$RAW_LOG" "$SAFE_LOG"
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  sed -i "s#${ANDROID_SERIAL}#<ANDROID_SERIAL>#g" "$SAFE_LOG" || true
fi

printf '==> Publicando diagnóstico no GitHub\n'
git fetch origin "$DIAGNOSTICS_BRANCH" >/dev/null 2>&1
git worktree add --detach "$DIAG_WORKTREE" "origin/$DIAGNOSTICS_BRANCH" >/dev/null 2>&1
mkdir -p "$DIAG_WORKTREE/$(dirname "$DIAGNOSTICS_FILE")"
{
  printf '# Último crash em runtime\n\n'
  printf -- '- Data UTC: `%s`\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  printf -- '- Branch local: `%s`\n' "$(git branch --show-current 2>/dev/null || echo desconhecida)"
  printf -- '- Commit local: `%s`\n' "$(git rev-parse HEAD 2>/dev/null || echo desconhecido)"
  printf -- '- Android: `%s`\n' "${DEVICE:-desconhecido}"
  printf -- '- Processo vivo após 3s: `%s`\n\n' "${PID:-não}"
  printf '```text\n'
  cat "$SAFE_LOG"
  printf '\n```\n'
} > "$DIAG_WORKTREE/$DIAGNOSTICS_FILE"
(
  cd "$DIAG_WORKTREE"
  git add "$DIAGNOSTICS_FILE"
  if ! git diff --cached --quiet; then
    git -c user.name='WA-Keeper Diagnostics' \
        -c user.email='wa-keeper-diagnostics@local' \
        commit -m 'diagnostics: registrar crash em runtime' >/dev/null
    git push origin "HEAD:refs/heads/$DIAGNOSTICS_BRANCH" >/dev/null 2>&1
  fi
)
git worktree remove --force "$DIAG_WORKTREE" >/dev/null 2>&1 || true

printf 'Diagnóstico publicado: %s / %s\n' "$DIAGNOSTICS_BRANCH" "$DIAGNOSTICS_FILE"
