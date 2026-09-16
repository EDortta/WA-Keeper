#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper"
VARIANT="release"
RUN_TESTS=1
LAUNCH_APP=1
TARGET_BRANCH=""
STAGE="inicialização"
LOG_FILE=""
DIAGNOSTICS_BRANCH="diagnostics/android-deploy"
DIAGNOSTICS_FILE="diagnostics/android-deploy/last-failure.md"

usage() {
  cat <<'EOF'
WA-Keeper Android deploy helper

Uso:
  bash scripts/android-deploy.sh [branch|alias] [opções]

Aliases:
  audio      feature/audio-arbiter-manual-tts
  motion     fix/motion-exit-detection
  schedule   feature/scheduled-time-media
  scheduled  feature/scheduled-time-media
  development development

Opções:
  --release      compila/instala release (padrão; preserva a assinatura esperada do app)
  --debug        compila/instala debug
  --skip-tests   não roda testes unitários antes do build
  --no-launch    instala mas não abre o app
  -h, --help     mostra esta ajuda

Exemplos:
  bash scripts/android-deploy.sh audio
  bash scripts/android-deploy.sh motion
  bash scripts/android-deploy.sh schedule
  bash scripts/android-deploy.sh development --release

Se houver mais de um Android conectado:
  ANDROID_SERIAL=<serial> bash scripts/android-deploy.sh audio

Em caso de falha nos testes, build ou instalação, o último erro é publicado em:
  branch: diagnostics/android-deploy
  arquivo: diagnostics/android-deploy/last-failure.md
EOF
}

log_line() {
  if [[ -n "${LOG_FILE:-}" ]]; then
    printf '%s\n' "$*" | tee -a "$LOG_FILE"
  else
    printf '%s\n' "$*"
  fi
}

publish_failure() {
  local status="${1:-1}"
  [[ -n "${REPO_ROOT:-}" && -n "${LOG_FILE:-}" && -f "$LOG_FILE" ]] || return 0

  local current_branch tested_branch tested_sha timestamp tmp_root diag_worktree safe_log publish_status
  current_branch="$(git branch --show-current 2>/dev/null || true)"
  tested_branch="${TARGET_BRANCH:-${current_branch:-desconhecida}}"
  tested_sha="$(git rev-parse HEAD 2>/dev/null || echo desconhecido)"
  timestamp="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-diagnostics.XXXXXX")"
  diag_worktree="$tmp_root/repo"
  safe_log="$tmp_root/output.log"

  # Não publique o caminho absoluto da home nem um serial eventualmente presente no output.
  sed "s#${HOME:-/home/unknown}#~#g" "$LOG_FILE" > "$safe_log" || cp "$LOG_FILE" "$safe_log"
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    sed -i "s#${ANDROID_SERIAL}#<ANDROID_SERIAL>#g" "$safe_log" || true
  fi

  # Mantém o diagnóstico fora das branches funcionais que estão sendo testadas.
  set +e
  git fetch origin "$DIAGNOSTICS_BRANCH" >/dev/null 2>&1
  git worktree add --detach "$diag_worktree" "origin/$DIAGNOSTICS_BRANCH" >/dev/null 2>&1
  publish_status=$?

  if (( publish_status == 0 )); then
    mkdir -p "$diag_worktree/$(dirname "$DIAGNOSTICS_FILE")"
    {
      printf '# Última falha do android-deploy\n\n'
      printf -- '- Data UTC: `%s`\n' "$timestamp"
      printf -- '- Branch testada: `%s`\n' "$tested_branch"
      printf -- '- Commit testado: `%s`\n' "$tested_sha"
      printf -- '- Variante: `%s`\n' "$VARIANT"
      printf -- '- Etapa: `%s`\n' "$STAGE"
      printf -- '- Código de saída: `%s`\n' "$status"
      printf -- '- Android: `%s`\n\n' "${DEVICE:-desconhecido}"
      printf '```text\n'
      tail -n 1200 "$safe_log"
      printf '\n```\n'
    } > "$diag_worktree/$DIAGNOSTICS_FILE"

    (
      cd "$diag_worktree" || exit 1
      git add "$DIAGNOSTICS_FILE" || exit 1
      if git diff --cached --quiet; then
        exit 0
      fi
      git -c user.name='WA-Keeper Deploy' \
          -c user.email='wa-keeper-deploy@local' \
          commit -m "diagnostics: registrar falha do android-deploy" >/dev/null || exit 1
      git push origin "HEAD:refs/heads/$DIAGNOSTICS_BRANCH" >/dev/null 2>&1
    )
    publish_status=$?
  fi

  git worktree remove --force "$diag_worktree" >/dev/null 2>&1 || true
  rm -rf "$tmp_root"
  set -e

  if (( publish_status == 0 )); then
    printf '\nDiagnóstico publicado no GitHub: %s / %s\n' "$DIAGNOSTICS_BRANCH" "$DIAGNOSTICS_FILE" >&2
  else
    printf '\nAVISO: não consegui publicar o diagnóstico no GitHub. O erro original foi preservado apenas no terminal.\n' >&2
  fi

  return 0
}

fail() {
  if [[ -n "${LOG_FILE:-}" ]]; then
    printf 'ERRO: %s\n' "$*" | tee -a "$LOG_FILE" >&2
    publish_failure 1
  else
    printf 'ERRO: %s\n' "$*" >&2
  fi
  exit 1
}

run_logged() {
  local stage="$1"
  shift
  local status=0

  STAGE="$stage"
  log_line "==> $stage"
  set +e
  "$@" 2>&1 | tee -a "$LOG_FILE"
  status=${PIPESTATUS[0]}
  set -e

  if (( status != 0 )); then
    publish_failure "$status"
    exit "$status"
  fi
}

while (($#)); do
  case "$1" in
    --release) VARIANT="release" ;;
    --debug) VARIANT="debug" ;;
    --skip-tests) RUN_TESTS=0 ;;
    --no-launch) LAUNCH_APP=0 ;;
    -h|--help) usage; exit 0 ;;
    -*) fail "opção desconhecida: $1" ;;
    *)
      [[ -z "$TARGET_BRANCH" ]] || fail "informe apenas uma branch/alias"
      TARGET_BRANCH="$1"
      ;;
  esac
  shift
done

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$REPO_ROOT"
LOG_FILE="$(mktemp "${TMPDIR:-/tmp}/wa-keeper-android-deploy.XXXXXX.log")"

[[ -x ./gradlew ]] || fail "./gradlew não encontrado ou não executável"
command -v adb >/dev/null 2>&1 || fail "adb não encontrado no PATH"

if [[ -n "$(git status --porcelain)" ]]; then
  fail "working tree tem alterações locais. Commit/stash antes de trocar ou compilar branches."
fi

case "$TARGET_BRANCH" in
  "") TARGET_BRANCH="$(git branch --show-current)" ;;
  audio) TARGET_BRANCH="feature/audio-arbiter-manual-tts" ;;
  motion) TARGET_BRANCH="fix/motion-exit-detection" ;;
  schedule|scheduled) TARGET_BRANCH="feature/scheduled-time-media" ;;
  development) TARGET_BRANCH="development" ;;
esac

[[ -n "$TARGET_BRANCH" ]] || fail "não consegui determinar a branch atual"

log_line "==> Atualizando origin/$TARGET_BRANCH"
git fetch origin "$TARGET_BRANCH"

if git show-ref --verify --quiet "refs/heads/$TARGET_BRANCH"; then
  git switch "$TARGET_BRANCH"
else
  git switch --track -c "$TARGET_BRANCH" "origin/$TARGET_BRANCH"
fi

git pull --ff-only origin "$TARGET_BRANCH"

if [[ "$VARIANT" == "release" ]]; then
  # build.gradle resolve RELEASE_STORE_FILE relativamente ao módulo app.
  STORE_FILE="$(sed -n 's/^RELEASE_STORE_FILE=//p' gradle.properties 2>/dev/null | tail -n 1 || true)"
  if [[ -n "$STORE_FILE" && ! -f "app/$STORE_FILE" && ! -f "$STORE_FILE" ]]; then
    fail "keystore de release '$STORE_FILE' não encontrado. Não vou cair para debug automaticamente, pois trocar assinatura pode exigir desinstalar o app e perder o banco local."
  fi
fi

ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=( -s "$ANDROID_SERIAL" )
else
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case "${#DEVICES[@]}" in
    0) fail "nenhum Android autorizado conectado via adb" ;;
    1) ADB+=( -s "${DEVICES[0]}" ) ;;
    *)
      printf 'Dispositivos conectados:\n' >&2
      printf '  %s\n' "${DEVICES[@]}" >&2
      fail "há mais de um Android. Rode com ANDROID_SERIAL=<serial>."
      ;;
  esac
fi

DEVICE="$(${ADB[@]} shell getprop ro.product.model | tr -d '\r')"
log_line "==> Android: ${DEVICE:-desconhecido}"
log_line "==> Branch: $TARGET_BRANCH"
log_line "==> Variante: $VARIANT"

if (( RUN_TESTS )); then
  run_logged "Testes unitários" ./gradlew --console=plain testDebugUnitTest
fi

if [[ "$VARIANT" == "release" ]]; then
  run_logged "Compilando release" ./gradlew --console=plain assembleRelease
  APK="app/build/outputs/apk/release/app-release.apk"
else
  run_logged "Compilando debug" ./gradlew --console=plain assembleDebug
  APK="app/build/outputs/apk/debug/app-debug.apk"
fi

[[ -f "$APK" ]] || fail "APK não encontrado em $APK"

STAGE="Instalação ADB"
log_line "==> Instalando sem apagar dados"
set +e
"${ADB[@]}" install -r "$APK" 2>&1 | tee -a "$LOG_FILE"
INSTALL_STATUS=${PIPESTATUS[0]}
set -e

if (( INSTALL_STATUS != 0 )); then
  cat <<'EOF' | tee -a "$LOG_FILE" >&2

A instalação falhou. NÃO desinstale o WA-Keeper para "resolver" assinatura incompatível:
a desinstalação apagaria o banco local. Corrija a assinatura/keystore e rode novamente.
EOF
  publish_failure "$INSTALL_STATUS"
  exit "$INSTALL_STATUS"
fi

if (( LAUNCH_APP )); then
  STAGE="Abertura do aplicativo"
  log_line "==> Abrindo WA-Keeper"
  "${ADB[@]}" shell am force-stop "$APP_ID" >/dev/null
  "${ADB[@]}" shell am start -n "$APP_ID/.MainActivity" >/dev/null
fi

printf '\n' | tee -a "$LOG_FILE"
printf 'OK: %s instalado no Android a partir de %s (%s).\n' "$APP_ID" "$TARGET_BRANCH" "$VARIANT" | tee -a "$LOG_FILE"
"${ADB[@]}" shell dumpsys package "$APP_ID" 2>/dev/null \
  | grep -E 'versionName=|versionCode=' \
  | head -n 2 \
  | sed 's/^/  /' || true

rm -f "$LOG_FILE"
