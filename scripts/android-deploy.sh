#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper"
VARIANT="release"
RUN_TESTS=1
LAUNCH_APP=1
TARGET_BRANCH=""

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
EOF
}

fail() {
  echo "ERRO: $*" >&2
  exit 1
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

echo "==> Atualizando origin/$TARGET_BRANCH"
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
echo "==> Android: ${DEVICE:-desconhecido}"
echo "==> Branch: $TARGET_BRANCH"
echo "==> Variante: $VARIANT"

if (( RUN_TESTS )); then
  echo "==> Testes unitários"
  ./gradlew --console=plain testDebugUnitTest
fi

if [[ "$VARIANT" == "release" ]]; then
  echo "==> Compilando release"
  ./gradlew --console=plain assembleRelease
  APK="app/build/outputs/apk/release/app-release.apk"
else
  echo "==> Compilando debug"
  ./gradlew --console=plain assembleDebug
  APK="app/build/outputs/apk/debug/app-debug.apk"
fi

[[ -f "$APK" ]] || fail "APK não encontrado em $APK"

echo "==> Instalando sem apagar dados"
set +e
INSTALL_OUTPUT="$(${ADB[@]} install -r "$APK" 2>&1)"
INSTALL_STATUS=$?
set -e
printf '%s\n' "$INSTALL_OUTPUT"

if (( INSTALL_STATUS != 0 )); then
  cat >&2 <<'EOF'

A instalação falhou. NÃO desinstale o WA-Keeper para "resolver" assinatura incompatível:
a desinstalação apagaria o banco local. Corrija a assinatura/keystore e rode novamente.
EOF
  exit "$INSTALL_STATUS"
fi

if (( LAUNCH_APP )); then
  echo "==> Abrindo WA-Keeper"
  ${ADB[@]} shell am force-stop "$APP_ID" >/dev/null
  ${ADB[@]} shell am start -n "$APP_ID/.MainActivity" >/dev/null
fi

echo
printf 'OK: %s instalado no Android a partir de %s (%s).\n' "$APP_ID" "$TARGET_BRANCH" "$VARIANT"
${ADB[@]} shell dumpsys package "$APP_ID" 2>/dev/null \
  | grep -E 'versionName=|versionCode=' \
  | head -n 2 \
  | sed 's/^/  /' || true
