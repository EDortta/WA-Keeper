#!/usr/bin/env bash
set -Eeuo pipefail

fail=0
bad(){ echo "BLOCK  $*" >&2; fail=$((fail+1)); }
ok(){ echo "OK     $*"; }

if git grep -nE 'RELEASE_(STORE_PASSWORD|KEY_PASSWORD)[[:space:]]*=' -- ':!release.properties.example' >/tmp/wa-sec-secrets.$$ 2>/dev/null; then
  bad "segredo de assinatura encontrado em arquivo rastreado"
  cat /tmp/wa-sec-secrets.$$ >&2
else
  ok "nenhuma senha de assinatura rastreada"
fi
rm -f /tmp/wa-sec-secrets.$$

tracked_sensitive="$(git ls-files | grep -E '(^|/)(private/|.*\.(keystore|jks|db|db-shm|db-wal|sqlite|sqlite3)$)|^wa-keeper-diagnose-' || true)"
if [[ -n "$tracked_sensitive" ]]; then
  bad "artefato privado/sensível rastreado"
  printf '%s\n' "$tracked_sensitive" >&2
else
  ok "nenhum artefato privado conhecido rastreado"
fi

direct_build="$(git grep -nE '(assembleDebug|assembleRelease|bundleRelease|:benchmark:assembleDebug)' -- 'scripts/*.sh' 'publisher/scripts/*.sh' '.github/workflows/*.yml' 2>/dev/null | grep -v 'scripts/build.sh' || true)"
if [[ -n "$direct_build" ]]; then
  bad "há compilação Android fora de scripts/build.sh"
  printf '%s\n' "$direct_build" >&2
else
  ok "scripts/build.sh é o único compilador"
fi

exit "$fail"
