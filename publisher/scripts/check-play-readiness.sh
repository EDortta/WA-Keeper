#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/app/build.gradle"

fail=0
warn=0

block() { printf 'BLOCK  %s\n' "$*"; fail=$((fail+1)); }
note()  { printf 'WARN   %s\n' "$*"; warn=$((warn+1)); }
ok()    { printf 'OK     %s\n' "$*"; }

[[ -f "$MANIFEST" ]] || block "AndroidManifest.xml não encontrado"
[[ -f "$GRADLE" ]] || block "app/build.gradle não encontrado"

if grep -q 'targetSdk 34' "$GRADLE"; then
  block "targetSdk ainda está em 34"
else
  ok "targetSdk não está fixado em 34"
fi

if grep -q 'signingConfig signingConfigs.debug' "$GRADLE"; then
  block "release ainda usa signingConfigs.debug"
else
  ok "release não usa explicitamente signingConfigs.debug"
fi

for permission in   MANAGE_EXTERNAL_STORAGE   BIND_ACCESSIBILITY_SERVICE   SCHEDULE_EXACT_ALARM   RECORD_AUDIO   READ_CONTACTS   INTERNET
do
  if grep -q "$permission" "$MANIFEST"; then
    note "permissão/recurso requer revisão: $permission"
  fi
done

if grep -qi 'never pass.*Play Store\|nunca passaria na Play Store\|nunca pasaría.*Play Store' "$ROOT/docs/index.html" 2>/dev/null; then
  block "docs/index.html ainda contém posicionamento anti-Play Store"
fi

printf '\nResumo: %d bloqueador(es), %d aviso(s).\n' "$fail" "$warn"

if (( fail > 0 )); then
  exit 1
fi
