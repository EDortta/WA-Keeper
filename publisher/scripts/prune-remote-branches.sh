#!/usr/bin/env bash
set -Eeuo pipefail

KEEP=(development play main)
REMOTE="${1:-origin}"

fail(){ echo "ERRO: $*" >&2; exit 1; }

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "rode dentro do repositório"
[[ -z "$(git status --porcelain)" ]] || fail "worktree tem alterações locais"
git remote get-url "$REMOTE" >/dev/null 2>&1 || fail "remote '$REMOTE' não existe"

echo "==> Atualizando refs remotos"
git fetch "$REMOTE" --prune

should_keep() {
  local b="$1"
  for k in "${KEEP[@]}"; do [[ "$b" == "$k" ]] && return 0; done
  return 1
}

mapfile -t BRANCHES < <(git ls-remote --heads "$REMOTE" | awk '{sub("refs/heads/","",$2); print $2}' | sort)

echo "Branches remotas atuais:"
printf '  %s\n' "${BRANCHES[@]}"
echo

DELETE=()
for b in "${BRANCHES[@]}"; do
  should_keep "$b" || DELETE+=("$b")
done

if (("${#DELETE[@]}" == 0)); then
  echo "Nada para apagar. Permanecem apenas development, play e main."
  exit 0
fi

echo "Serão removidas:"
printf '  %s\n' "${DELETE[@]}"
echo
echo "Serão preservadas:"
printf '  %s\n' "${KEEP[@]}"
echo

for b in "${DELETE[@]}"; do
  git push "$REMOTE" --delete "$b"
done

echo "==> Limpando refs locais obsoletas"
git fetch "$REMOTE" --prune

echo
echo "LIMPEZA DE BRANCHES CONCLUÍDA"
echo "Remoto preservado: development, play, main"
