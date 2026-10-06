#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

branch="$(git rev-parse --abbrev-ref HEAD)"
version_name="$(grep -E 'versionName ' app/build.gradle | head -1 | sed -E 's/.*versionName[[:space:]]+"([^"]+)".*/\1/')"
version_code="$(grep -E 'versionCode ' app/build.gradle | head -1 | awk '{print $2}')"

echo "branch=$branch"
echo "commit=$(git rev-parse HEAD)"
echo "versionName=$version_name"
echo "versionCode=$version_code"

if [[ "$branch" == "main" ]]; then
  tag="v$version_name"
  if git rev-parse "$tag" >/dev/null 2>&1; then
    echo "tag=$tag OK"
  else
    echo "WARN: tag $tag ainda não existe"
  fi
fi

aab="$(find app/build/outputs/bundle -type f -name '*.aab' 2>/dev/null | head -1 || true)"
if [[ -n "$aab" ]]; then
  echo "aab=$aab"
  sha256sum "$aab"
else
  echo "WARN: nenhum AAB encontrado"
fi
