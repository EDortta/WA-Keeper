#!/usr/bin/env bash
set -Eeuo pipefail

EXPECTED_HOST="devel3"
BRANCH="development"
APK_SOURCE="app/build/outputs/apk/release/app-release.apk"

fail() {
  echo "ERRO: $*" >&2
  exit 1
}

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" ||
  fail "execute dentro do repositório WA-Keeper"
cd "$REPO_ROOT"

HOST_SHORT="$(hostname -s 2>/dev/null || hostname)"
if [[ "$HOST_SHORT" != "$EXPECTED_HOST" ]]; then
  fail "este script foi criado para o host '$EXPECTED_HOST' (host atual: '$HOST_SHORT')"
fi

[[ -x ./gradlew ]] || fail "./gradlew não encontrado ou não executável"

if [[ -n "$(git status --porcelain)" ]]; then
  fail "há alterações locais. Commit/stash antes de gerar o APK."
fi

echo "==> Atualizando $BRANCH"
git fetch origin "$BRANCH"
git switch "$BRANCH"
git pull --ff-only origin "$BRANCH"

DEBUG_KEYSTORE="${HOME}/.android/debug.keystore"
[[ -f "$DEBUG_KEYSTORE" ]] ||
  fail "keystore histórica não encontrada em $DEBUG_KEYSTORE"

echo "==> Assinatura local usada pelo build"
if command -v keytool >/dev/null 2>&1; then
  keytool -list -v \
    -keystore "$DEBUG_KEYSTORE" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android 2>/dev/null \
    | grep -E 'Alias name:|SHA256:' \
    | sed 's/^/    /' || true
fi

echo "==> Rodando testes"
./gradlew --console=plain testDebugUnitTest

echo "==> Compilando release no devel3"
./gradlew --console=plain assembleRelease

[[ -f "$APK_SOURCE" ]] || fail "APK não encontrado em $APK_SOURCE"

echo "==> Validando assinatura do APK"
APKSIGNER=""
if command -v apksigner >/dev/null 2>&1; then
  APKSIGNER="$(command -v apksigner)"
else
  SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$SDK_ROOT" && -f local.properties ]]; then
    SDK_ROOT="$(sed -n 's/^sdk.dir=//p' local.properties | tail -n1 | sed 's#\\:#:#g; s#\\\\#/#g')"
  fi
  if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/build-tools" ]]; then
    APKSIGNER="$(find "$SDK_ROOT/build-tools" -maxdepth 2 -type f -name apksigner -perm -u+x 2>/dev/null | sort -V | tail -n1)"
  fi
fi

if [[ -n "$APKSIGNER" ]]; then
  "$APKSIGNER" verify --print-certs "$APK_SOURCE" \
    | grep -E 'Signer #1 certificate (DN|SHA-256 digest):' \
    | sed 's/^/    /'
else
  echo "    apksigner não encontrado; build concluído sem exibir o certificado."
fi

echo
echo "PRONTO"
echo "  APK: $REPO_ROOT/$APK_SOURCE"
echo "  Nenhum APK, ZIP ou SHA foi copiado para o repositório."
echo "  Nenhum commit ou push foi realizado pelo script."
