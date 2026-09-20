#!/usr/bin/env bash
set -Eeuo pipefail

EXPECTED_HOST="devel3"
BRANCH="development"
RELEASE_DIR="releases"
APK_SOURCE="app/build/outputs/apk/release/app-release.apk"
APK_DEST="$RELEASE_DIR/WA-Keeper-latest.apk"
ZIP_DEST="$RELEASE_DIR/WA-Keeper-latest.zip"
SHA_DEST="$RELEASE_DIR/WA-Keeper-latest.sha256"

fail() {
  echo "ERRO: $*" >&2
  exit 1
}

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$REPO_ROOT"

HOST_SHORT="$(hostname -s 2>/dev/null || hostname)"
if [[ "$HOST_SHORT" != "$EXPECTED_HOST" ]]; then
  fail "este script foi criado para o host '$EXPECTED_HOST' (host atual: '$HOST_SHORT')"
fi

[[ -x ./gradlew ]] || fail "./gradlew não encontrado ou não executável"
command -v zip >/dev/null 2>&1 || fail "comando 'zip' não encontrado"
command -v sha256sum >/dev/null 2>&1 || fail "comando 'sha256sum' não encontrado"

if [[ -n "$(git status --porcelain)" ]]; then
  fail "há alterações locais. Commit/stash antes de gerar o APK."
fi

echo "==> Atualizando $BRANCH"
git fetch origin "$BRANCH"
git switch "$BRANCH"
git pull --ff-only origin "$BRANCH"

DEBUG_KEYSTORE="${HOME}/.android/debug.keystore"
[[ -f "$DEBUG_KEYSTORE" ]] || fail "keystore histórica não encontrada em $DEBUG_KEYSTORE"

echo "==> Assinatura local usada pelo build"
if command -v keytool >/dev/null 2>&1; then
  keytool -list -v     -keystore "$DEBUG_KEYSTORE"     -storepass android     -alias androiddebugkey     -keypass android 2>/dev/null     | grep -E 'Alias name:|SHA256:'     | sed 's/^/    /' || true
fi

echo "==> Rodando testes"
./gradlew --console=plain testDebugUnitTest

echo "==> Compilando release no devel3"
./gradlew --console=plain assembleRelease

[[ -f "$APK_SOURCE" ]] || fail "APK não encontrado em $APK_SOURCE"

mkdir -p "$RELEASE_DIR"
cp -f "$APK_SOURCE" "$APK_DEST"

echo "==> Gerando ZIP"
rm -f "$ZIP_DEST"
zip -j -q "$ZIP_DEST" "$APK_DEST"

echo "==> Gerando SHA-256"
sha256sum "$APK_DEST" | sed 's#  releases/#  #' > "$SHA_DEST"

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
  "$APKSIGNER" verify --print-certs "$APK_DEST"     | grep -E 'Signer #1 certificate (DN|SHA-256 digest):'     | sed 's/^/    /'
else
  echo "    apksigner não encontrado; build concluído sem exibir o certificado."
fi

git add "$APK_DEST" "$ZIP_DEST" "$SHA_DEST"

if git diff --cached --quiet; then
  echo "==> APK/ZIP já correspondem ao conteúdo publicado."
else
  VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]+"([^"]+)".*/\\1/p' app/build.gradle | head -n1)"
  [[ -n "$VERSION" ]] || VERSION="unknown"

  echo "==> Commitando artefatos"
  git commit -m "build: publish WA-Keeper $VERSION from devel3"

  echo "==> Publicando em origin/$BRANCH"
  git push origin "$BRANCH"
fi

echo
echo "PRONTO"
echo "  APK: $APK_DEST"
echo "  ZIP: $ZIP_DEST"
echo "  SHA: $SHA_DEST"
echo "  Download ZIP:"
echo "  https://raw.githubusercontent.com/EDortta/WA-Keeper/development/releases/WA-Keeper-latest.zip"
