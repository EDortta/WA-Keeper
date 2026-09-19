#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERRO: execute dentro do repositório WA-Keeper" >&2
  exit 1
}
cd "$REPO_ROOT"

APK="app/build/outputs/apk/release/app-release.apk"
DEST_DIR="releases"
DEST_APK="$DEST_DIR/WA-Keeper-latest.apk"
DEST_SHA="$DEST_DIR/WA-Keeper-latest.sha256"
HTTPS_REMOTE="https://github.com/EDortta/WA-Keeper.git"

[[ "$(git branch --show-current)" == "development" ]] || {
  echo "ERRO: rode na branch development" >&2
  exit 1
}

[[ -f "$APK" ]] || {
  echo "APK ainda não existe; compilando release..."
  ./gradlew --console=plain assembleRelease
}

mkdir -p "$DEST_DIR"
cp -f "$APK" "$DEST_APK"
sha256sum "$DEST_APK" | sed 's#  releases/#  #' > "$DEST_SHA"

git add "$DEST_APK" "$DEST_SHA"

if git diff --cached --quiet; then
  echo "APK do GitHub já está atualizado."
  exit 0
fi

VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]+"([^"]+)".*/\\1/p' app/build.gradle | head -n1)"
[[ -n "$VERSION" ]] || VERSION="unknown"

git commit -m "build: publish WA-Keeper ${VERSION} APK"

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  gh auth setup-git >/dev/null 2>&1 || true
fi

echo "Publicando APK no GitHub..."
git push "$HTTPS_REMOTE" development

echo
echo "OK: releases/WA-Keeper-latest.apk"
echo "GitHub: https://github.com/EDortta/WA-Keeper/blob/development/releases/WA-Keeper-latest.apk"
