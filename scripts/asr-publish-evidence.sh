#!/usr/bin/env bash
set -Eeuo pipefail

STATUS="${1:-1}"
STAGE="${2:-desconhecida}"
DEVICE="${3:-desconhecido}"
OUT_DIR="${4:-}"
CONSOLE_LOG="${5:-}"
ANDROID_MODELS="${6:-tiny,base,small}"

DIAGNOSTICS_BRANCH="diagnostics/asr-benchmark"
DIAGNOSTICS_PATH="diagnostics/asr-benchmark/latest"

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
cd "$REPO_ROOT"

tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-asr-publish.XXXXXX")"
diag_worktree="$tmp_root/repo"

cleanup() {
  git worktree remove --force "$diag_worktree" >/dev/null 2>&1 || true
  rm -rf "$tmp_root"
}
trap cleanup EXIT

git fetch origin "$DIAGNOSTICS_BRANCH" >/dev/null 2>&1 || true
if git show-ref --verify --quiet "refs/remotes/origin/$DIAGNOSTICS_BRANCH"; then
  base_ref="origin/$DIAGNOSTICS_BRANCH"
else
  base_ref="$(git rev-parse HEAD)"
fi

git worktree add --detach "$diag_worktree" "$base_ref" >/dev/null 2>&1 || exit 0

rm -rf "$diag_worktree/$DIAGNOSTICS_PATH"
mkdir -p "$diag_worktree/$DIAGNOSTICS_PATH"

if [[ -n "$CONSOLE_LOG" && -f "$CONSOLE_LOG" ]]; then
  cp "$CONSOLE_LOG" "$diag_worktree/$DIAGNOSTICS_PATH/console.log"
fi

{
  printf '# Último benchmark ASR\n\n'
  printf -- '- Data: %s\n' "$(date -Iseconds)"
  printf -- '- Status: %s\n' "$STATUS"
  printf -- '- Etapa: %s\n' "$STAGE"
  printf -- '- Android: %s\n' "$DEVICE"
  printf -- '- Commit: %s\n' "$(git rev-parse HEAD)"
  printf -- '- Modelos Android: %s\n' "$ANDROID_MODELS"
  printf -- '- Referência devel3: faster-whisper small\n\n'
  if [[ "$STATUS" == "0" ]]; then
    printf 'Benchmark concluído. Consulte report.md e metrics.json.\n'
  else
    printf 'Benchmark interrompido. Consulte console.log.\n'
  fi
} > "$diag_worktree/$DIAGNOSTICS_PATH/run.md"

if [[ -n "$OUT_DIR" && -d "$OUT_DIR" ]]; then
  for name in report.md metrics.json samples.tsv; do
    [[ -f "$OUT_DIR/$name" ]] &&
      cp "$OUT_DIR/$name" "$diag_worktree/$DIAGNOSTICS_PATH/$name"
  done

  if [[ -d "$OUT_DIR/logs" ]]; then
    mkdir -p "$diag_worktree/$DIAGNOSTICS_PATH/logs"
    cp "$OUT_DIR"/logs/*.log "$diag_worktree/$DIAGNOSTICS_PATH/logs/" 2>/dev/null || true
  fi

  if [[ -d "$OUT_DIR/reference" ]]; then
    while IFS= read -r src; do
      rel="${src#"$OUT_DIR/"}"
      mkdir -p "$diag_worktree/$DIAGNOSTICS_PATH/$(dirname "$rel")"
      cp "$src" "$diag_worktree/$DIAGNOSTICS_PATH/$rel"
    done < <(find "$OUT_DIR/reference" -type f \( -name 'transcript.txt' -o -name 'elapsed-ms.txt' \) 2>/dev/null)
  fi

  if [[ -d "$OUT_DIR/android" ]]; then
    while IFS= read -r src; do
      rel="${src#"$OUT_DIR/"}"
      mkdir -p "$diag_worktree/$DIAGNOSTICS_PATH/$(dirname "$rel")"
      cp "$src" "$diag_worktree/$DIAGNOSTICS_PATH/$rel"
    done < <(find "$OUT_DIR/android" -type f \( -name '*.json' -o -name '*.txt' \) 2>/dev/null)
  fi
fi

(
  cd "$diag_worktree"
  git add "$DIAGNOSTICS_PATH"
  if git diff --cached --quiet; then
    exit 0
  fi
  git -c user.name='WA-Keeper ASR Benchmark'       -c user.email='wa-keeper-asr@local'       commit -m "diagnostics: atualizar benchmark ASR" >/dev/null
  git push origin "HEAD:refs/heads/$DIAGNOSTICS_BRANCH" >/dev/null 2>&1
)

printf 'Evidências publicadas em %s:%s\n' "$DIAGNOSTICS_BRANCH" "$DIAGNOSTICS_PATH" >&2
