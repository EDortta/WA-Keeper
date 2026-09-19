#!/usr/bin/env bash
set -Eeuo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  echo "ERRO: execute dentro do repositório WA-Keeper" >&2
  exit 1
}
cd "$REPO_ROOT"

DEST_DIR="releases"
DEST_APK="$DEST_DIR/WA-Keeper-latest.apk"
DEST_ZIP="$DEST_DIR/WA-Keeper-latest.zip"
DEST_SHA="$DEST_DIR/WA-Keeper-latest.sha256"
HTTPS_REMOTE="https://github.com/EDortta/WA-Keeper.git"

[[ "$(git branch --show-current)" == "development" ]] || {
  echo "ERRO: rode na branch development" >&2
  exit 1
}

bash scripts/package-apk.sh

git add "$DEST_APK" "$DEST_ZIP" "$DEST_SHA"

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
echo "OK: releases/WA-Keeper-latest.zip"
echo "Download APK: https://raw.githubusercontent.com/EDortta/WA-Keeper/development/releases/WA-Keeper-latest.apk"
echo "Download ZIP: https://raw.githubusercontent.com/EDortta/WA-Keeper/development/releases/WA-Keeper-latest.zip"
