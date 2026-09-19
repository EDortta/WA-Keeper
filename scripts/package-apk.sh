#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERRO: execute dentro do repositório WA-Keeper" >&2
  exit 1
}
cd "$REPO_ROOT"

[[ -x ./gradlew ]] || {
  echo "ERRO: ./gradlew não encontrado ou não executável" >&2
  exit 1
}
command -v zip >/dev/null 2>&1 || {
  echo "ERRO: comando zip não encontrado" >&2
  exit 1
}

APK_SOURCE="app/build/outputs/apk/release/app-release.apk"
RELEASE_DIR="releases"
APK_DEST="$RELEASE_DIR/WA-Keeper-latest.apk"
ZIP_DEST="$RELEASE_DIR/WA-Keeper-latest.zip"
SHA_DEST="$RELEASE_DIR/WA-Keeper-latest.sha256"

echo "==> Testes unitários"
./gradlew --console=plain testDebugUnitTest

echo "==> Compilando APK release"
./gradlew --console=plain assembleRelease

[[ -f "$APK_SOURCE" ]] || {
  echo "ERRO: APK não encontrado em $APK_SOURCE" >&2
  exit 1
}

mkdir -p "$RELEASE_DIR"
cp -f "$APK_SOURCE" "$APK_DEST"

echo "==> Gerando ZIP"
rm -f "$ZIP_DEST"
zip -j -q "$ZIP_DEST" "$APK_DEST"

echo "==> Gerando SHA-256"
sha256sum "$APK_DEST" | sed 's#  releases/#  #' > "$SHA_DEST"

git add "$APK_DEST" "$ZIP_DEST" "$SHA_DEST"

echo
echo "Pronto:"
echo "  $APK_DEST"
echo "  $ZIP_DEST"
echo "  $SHA_DEST"
echo
echo "Os artefatos já estão dentro do repositório e adicionados ao git index."
