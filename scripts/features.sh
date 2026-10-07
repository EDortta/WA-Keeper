#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "ERRO: execute dentro do repositório WA-Keeper" >&2; exit 1; }
cd "$ROOT"

command -v python3 >/dev/null 2>&1 || { echo "ERRO: python3 não encontrado" >&2; exit 1; }
[[ -t 0 && -t 1 ]] || { echo "ERRO: este painel precisa de um terminal interativo" >&2; exit 1; }

exec python3 publisher/scripts/features-menu.py
