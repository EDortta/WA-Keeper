#!/usr/bin/env bash
set -Eeuo pipefail

fail(){ echo "ERRO: $*" >&2; exit 1; }

MODE="${1:---dry-run}"
[[ "$MODE" == "--dry-run" || "$MODE" == "--execute" ]] || fail "uso: bash publisher/scripts/purge-sensitive-history.sh [--dry-run|--execute]"

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"

command -v git >/dev/null 2>&1 || fail "git não encontrado"
command -v git-filter-repo >/dev/null 2>&1 || fail "git-filter-repo não encontrado"

[[ -z "$(git status --porcelain)" ]] || fail "worktree tem alterações locais; faça commit/stash antes"
ORIGIN="$(git remote get-url origin)"
[[ -n "$ORIGIN" ]] || fail "origin não encontrado"

STAMP="$(date '+%Y-%m-%d-%H-%M-%S')"
WORK="${TMPDIR:-/tmp}/wa-keeper-history-purge-$STAMP"
MIRROR="$WORK/WA-Keeper.git"
mkdir -p "$WORK"

echo "==> Criando mirror temporário"
git clone --mirror "$ORIGIN" "$MIRROR"
cd "$MIRROR"

echo "==> Salvando refs originais"
git show-ref > "$WORK/refs-before.txt"

echo "==> Reescrevendo histórico no mirror temporário"
git filter-repo --force   --path-glob 'wa-keeper-diagnose-*'   --invert-paths   --blob-callback '
lines = blob.data.splitlines(keepends=True)
blocked = (
    b"RELEASE_STORE_FILE=",
    b"RELEASE_KEY_ALIAS=",
    b"RELEASE_STORE_PASSWORD=",
    b"RELEASE_KEY_PASSWORD=",
)
blob.data = b"".join(line for line in lines if not line.lstrip().startswith(blocked))
'

echo "==> Verificando que diagnósticos privados não permanecem alcançáveis"
if git rev-list --objects --all | grep -E '(^|/)wa-keeper-diagnose-[^/]*(/|$)' > "$WORK/remaining-diagnostics.txt"; then
  cat "$WORK/remaining-diagnostics.txt"
  fail "ainda existem objetos de diagnóstico alcançáveis"
fi

echo "==> Verificando padrões RELEASE_* no histórico reescrito"
FOUND=0
while read -r sha path; do
  [[ -n "$sha" ]] || continue
  if git cat-file -p "$sha" 2>/dev/null | grep -aE '^[[:space:]]*RELEASE_(STORE_FILE|KEY_ALIAS|STORE_PASSWORD|KEY_PASSWORD)=' >/dev/null; then
    echo "$sha $path" >> "$WORK/remaining-release-lines.txt"
    FOUND=1
  fi
done < <(git rev-list --objects --all)
[[ "$FOUND" -eq 0 ]] || { cat "$WORK/remaining-release-lines.txt"; fail "ainda existem linhas RELEASE_* alcançáveis"; }

echo "==> Refs após reescrita"
git show-ref > "$WORK/refs-after.txt"

echo
echo "HISTÓRICO LIMPO NO MIRROR TEMPORÁRIO"
echo "Mirror: $MIRROR"
echo "Refs originais: $WORK/refs-before.txt"
echo "Refs reescritas: $WORK/refs-after.txt"

if [[ "$MODE" == "--dry-run" ]]; then
  echo
  echo "DRY-RUN: nada foi enviado ao GitHub."
  echo "Revise o resultado. Para executar a troca remota:"
  echo "  bash publisher/scripts/purge-sensitive-history.sh --execute"
  exit 0
fi

echo
echo "ATENÇÃO: iniciando force push de todos os refs reescritos."
git remote add cleaned "$ORIGIN"
git push --force --mirror cleaned

echo
echo "HISTÓRICO REMOTO REESCRITO"
echo "Todos os clones antigos devem ser descartados ou realinhados ao novo histórico."
