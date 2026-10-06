#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

branch="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$branch" != "play" && "$branch" != "main" ]]; then
  echo "ERRO: build Play só é permitido em play ou main. Branch atual: $branch" >&2
  exit 1
fi

bash publisher/scripts/check-play-readiness.sh

if [[ -x ./gradlew ]]; then
  ./gradlew clean bundleRelease
else
  echo "ERRO: gradlew não encontrado ou não executável." >&2
  exit 1
fi

echo
echo "AAB gerado em:"
find app/build/outputs/bundle -type f -name '*.aab' -print
