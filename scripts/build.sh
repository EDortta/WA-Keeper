#!/usr/bin/env bash
set -Eeuo pipefail

fail(){ echo "ERRO: $*" >&2; exit 1; }

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$ROOT"

REGISTRY="$ROOT/publisher/features.properties"
[[ -f "$REGISTRY" ]] || fail "registro de features não encontrado: $REGISTRY"
[[ -x ./gradlew ]] || fail "./gradlew não encontrado ou não executável"

PROFILE="lab"
BUILD_TYPE="debug"
MODE="apk"
TARGET="app"
ONLY=""
ONLY_SET=0
PRINT_ONLY=0
RUN_TESTS=1
EVIDENCE_MODE="${WA_EVIDENCE_MODE:-local}"
FEATURE_ID="${WA_FEATURE_ID:-general}"
FEATURE_CSV=""
declare -a ENABLE=()
declare -a DISABLE=()

prop(){ local key="$1"; sed -n "s/^${key}=//p" "$REGISTRY" | tail -n1; }

while (($#)); do
  case "$1" in
    --lab) PROFILE="lab" ;;
    --store) PROFILE="store"; BUILD_TYPE="release"; MODE="bundle" ;;
    --feature) shift; [[ $# -gt 0 ]] || fail "--feature exige nome"; ENABLE+=("$1") ;;
    --no-feature) shift; [[ $# -gt 0 ]] || fail "--no-feature exige nome"; DISABLE+=("$1") ;;
    --only) shift; [[ $# -gt 0 ]] || fail "--only exige lista"; ONLY="$1"; ONLY_SET=1 ;;
    --app) TARGET="app" ;;
    --benchmark) TARGET="benchmark"; PROFILE="lab"; BUILD_TYPE="debug"; MODE="apk" ;;
    --godofredo-benchmark) TARGET="godofredo-benchmark"; PROFILE="lab"; BUILD_TYPE="debug"; MODE="apk" ;;
    --debug) BUILD_TYPE="debug"; MODE="apk" ;;
    --release) BUILD_TYPE="release"; MODE="apk" ;;
    --bundle) BUILD_TYPE="release"; MODE="bundle" ;;
    --skip-tests) RUN_TESTS=0 ;;
    --evidence=local) EVIDENCE_MODE=local ;;
    --evidence=remote) EVIDENCE_MODE=remote ;;
    --evidence=off) EVIDENCE_MODE=off ;;
    --print) PRINT_ONLY=1 ;;
    -h|--help) echo "uso: bash scripts/build.sh [--lab|--store] [--feature N] [--no-feature N] [--only a,b] [--app|--benchmark|--godofredo-benchmark] [--debug|--release|--bundle] [--skip-tests] [--evidence=local|remote|off] [--print]"; exit 0 ;;
    *) fail "opção desconhecida: $1" ;;
  esac
  shift
done

# Local manifests are scoped to this worktree, never shared with another checkout.
EVIDENCE_DIR="$ROOT/.git-local-build"
if [[ -f "$ROOT/.git" ]]; then
  COMMON_GIT_DIR="$(git rev-parse --git-path HEAD)"
  EVIDENCE_DIR="$(dirname "$COMMON_GIT_DIR")/wa-build"
else
  EVIDENCE_DIR="$ROOT/.git/wa-build"
fi
BUILD_START="$(date +%s)"
BUILD_LOG=""
record_build() {
  local exit_status=$?
  trap - EXIT
  if [[ "$PRINT_ONLY" == 0 ]]; then
    local elapsed=$(( $(date +%s) - BUILD_START ))
    local sha branch
    sha="$(git rev-parse HEAD)"
    branch="$(git branch --show-current)"
    # The manifest is written only for a successful APK build.
    if (( exit_status == 0 )) && [[ "$MODE" == "apk" && "$TARGET" == "app" ]]; then
      local artifact="app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk"
      [[ -f "$artifact" ]] || { echo "ERRO: APK ausente após compilação" >&2; exit_status=1; }
      if (( exit_status == 0 )); then
      mkdir -p "$EVIDENCE_DIR"
      local digest
      digest="$(sha256sum "$artifact" | awk '{print $1}')"
      local manifest_tmp="$EVIDENCE_DIR/last-success.tmp"
      printf 'profile=%s\ntype=%s\nmode=%s\ntarget=%s\nfeatures=%s\ncommit=%s\nartifact=%s\nsha256=%s\n' \
        "$PROFILE" "$BUILD_TYPE" "$MODE" "$TARGET" "$FEATURE_CSV" "$sha" "$artifact" "$digest" > "$manifest_tmp"
      mv -f "$manifest_tmp" "$EVIDENCE_DIR/last-success"
      fi
    fi
    local args=(record --project WA-Keeper --feature "$FEATURE_ID" --branch "$branch" --commit "$sha" --target "$TARGET" --profile "$PROFILE" --build-type "$BUILD_TYPE" --mode "$MODE" --exit-code "$exit_status" --duration "$elapsed")
    [[ "$EVIDENCE_MODE" == "remote" ]] && args+=(--remote)
    if [[ "$EVIDENCE_MODE" != "off" ]]; then
      python3 "$ROOT/scripts/evidence.py" "${args[@]}" || echo "Aviso: falha ao enfileirar evidência" >&2
    fi
    [[ -n "$BUILD_LOG" ]] && echo "Build log local: $BUILD_LOG"
  fi
  exit "$exit_status"
}
run_gradle() {
  if [[ "$EVIDENCE_MODE" != "off" ]]; then
    ./gradlew --console=plain "$@" 2>&1 | tee -a "$BUILD_LOG"
  else
    ./gradlew --console=plain "$@"
  fi
}
start_recording() {
  [[ "$PRINT_ONLY" == 1 ]] && return
  if [[ "$EVIDENCE_MODE" == "off" ]]; then
    trap record_build EXIT
    return
  fi
  local logdir="${XDG_STATE_HOME:-$HOME/.local/state}/wa-keeper/build-logs"
  (umask 077; mkdir -p "$logdir")
  BUILD_LOG="$(mktemp "$logdir/build-XXXXXXXX.log")"
  chmod 600 "$BUILD_LOG"
  trap record_build EXIT
}

if [[ "$TARGET" == "benchmark" || "$TARGET" == "godofredo-benchmark" ]]; then
  [[ "$PROFILE" != "store" ]] || fail "benchmark não é artefato de loja"
  echo "WA Keeper build"
  echo "  target=$TARGET"
  echo "  profile=lab"
  echo "  type=debug"
  (( PRINT_ONLY )) && exit 0
  start_recording
  run_gradle ":$TARGET:assembleDebug"
  exit 0
fi

IFS=',' read -r -a ALL_FEATURES <<< "$(prop features)"
declare -A KNOWN=() ENABLED=()
for f in "${ALL_FEATURES[@]}"; do KNOWN["$f"]=1; done
validate_name(){ [[ -n "${KNOWN[$1]:-}" ]] || fail "feature desconhecida: $1"; }

if (( ONLY_SET )); then
  if [[ -n "$ONLY" ]]; then
    IFS=',' read -r -a selected <<< "$ONLY"
    for f in "${selected[@]}"; do validate_name "$f"; ENABLED["$f"]=1; done
  fi
else
  for f in "${ALL_FEATURES[@]}"; do
    if [[ "$PROFILE" == "store" ]]; then
      [[ "$(prop "feature.$f.storeDefault")" == "true" ]] && ENABLED["$f"]=1
    else
      [[ "$(prop "feature.$f.labDefault")" == "true" ]] && ENABLED["$f"]=1
    fi
  done
fi

for f in "${ENABLE[@]}"; do validate_name "$f"; ENABLED["$f"]=1; done
for f in "${DISABLE[@]}"; do validate_name "$f"; unset 'ENABLED[$f]'; done

if [[ "$PROFILE" == "store" ]]; then
  for f in "${!ENABLED[@]}"; do
    [[ "$(prop "feature.$f.storeAllowed")" == "true" ]] || fail "contradição: build STORE solicitou feature não habilitada para loja: $f"
  done
fi

FEATURE_CSV=""
for f in "${ALL_FEATURES[@]}"; do
  if [[ -n "${ENABLED[$f]:-}" ]]; then
    [[ -z "$FEATURE_CSV" ]] || FEATURE_CSV+=","
    FEATURE_CSV+="$f"
  fi
done

STORE=false
[[ "$PROFILE" == "store" ]] && STORE=true

echo "WA Keeper build"
echo "  target=app"
echo "  profile=$PROFILE"
echo "  store=$STORE"
echo "  type=$BUILD_TYPE"
echo "  mode=$MODE"
echo "  features=${FEATURE_CSV:-<none>}"
echo "  defaults: target=app profile=lab type=debug mode=apk"
echo "  evidence=$EVIDENCE_MODE"

(( PRINT_ONLY )) && exit 0
start_recording

ARGS=("-PwaStore=$STORE" "-PwaFeatures=$FEATURE_CSV")

if (( RUN_TESTS )); then
  run_gradle ":app:testDebugUnitTest" "${ARGS[@]}"
fi

if [[ "$MODE" == "bundle" ]]; then
  TASK="bundleRelease"
elif [[ "$BUILD_TYPE" == "debug" ]]; then
  TASK="assembleDebug"
else
  TASK="assembleRelease"
fi

run_gradle ":app:$TASK" "${ARGS[@]}"
