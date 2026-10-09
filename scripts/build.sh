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
PRINT_ONLY=0
RUN_TESTS=1
EVIDENCE=1
declare -a ENABLE=()
declare -a DISABLE=()

prop(){ local key="$1"; sed -n "s/^${key}=//p" "$REGISTRY" | tail -n1; }

while (($#)); do
  case "$1" in
    --lab) PROFILE="lab" ;;
    --store) PROFILE="store"; BUILD_TYPE="release"; MODE="bundle" ;;
    --feature) shift; [[ $# -gt 0 ]] || fail "--feature exige nome"; ENABLE+=("$1") ;;
    --no-feature) shift; [[ $# -gt 0 ]] || fail "--no-feature exige nome"; DISABLE+=("$1") ;;
    --only) shift; [[ $# -gt 0 ]] || fail "--only exige lista"; ONLY="$1" ;;
    --app) TARGET="app" ;;
    --benchmark) TARGET="benchmark"; PROFILE="lab"; BUILD_TYPE="debug"; MODE="apk" ;;
    --godofredo-benchmark) TARGET="godofredo-benchmark"; PROFILE="lab"; BUILD_TYPE="debug"; MODE="apk" ;;
    --debug) BUILD_TYPE="debug"; MODE="apk" ;;
    --release) BUILD_TYPE="release"; MODE="apk" ;;
    --bundle) BUILD_TYPE="release"; MODE="bundle" ;;
    --skip-tests) RUN_TESTS=0 ;;
    --no-evidence) EVIDENCE=0 ;;
    --print) PRINT_ONLY=1 ;;
    -h|--help) echo "uso: bash scripts/build.sh [--lab|--store] [--feature N] [--no-feature N] [--only a,b] [--app|--benchmark|--godofredo-benchmark] [--debug|--release|--bundle] [--skip-tests] [--no-evidence] [--print]"; exit 0 ;;
    *) fail "opção desconhecida: $1" ;;
  esac
  shift
done

# Evidence stays outside the repository. Never commit raw build output without review.
# Every command is piped through tee with pipefail enabled, preserving failure status.
start_evidence() {
  (( EVIDENCE && ! PRINT_ONLY )) || return 0
  local evidence_dir="${XDG_STATE_HOME:-$HOME/.local/state}/wa-keeper/builds"
  (umask 077; mkdir -p "$evidence_dir") || fail "não foi possível criar pasta de evidências"
  chmod 700 "$evidence_dir" || fail "não foi possível proteger pasta de evidências"
  local date_part sha_part
  date_part="$(date -u +%Y%m%dT%H%M%SZ)"
  sha_part="$(git rev-parse --short=12 HEAD)"
  EVIDENCE_LOG="$(mktemp "$evidence_dir/${date_part}-${sha_part}-XXXXXXXX.log")" || fail "não foi possível criar evidência"
  chmod 600 "$EVIDENCE_LOG"
  printf 'branch=%s\ncommit=%s\nstart_utc=%s\n' "$(git branch --show-current)" "$(git rev-parse HEAD)" "$(date -u +%FT%TZ)" >> "$EVIDENCE_LOG"
  echo "  evidence=$EVIDENCE_LOG (local, não versionado)"
  trap 'code=$?; if [[ -n "${EVIDENCE_LOG:-}" ]]; then printf "end_utc=%s\\nexit_code=%s\\n" "$(date -u +%FT%TZ)" "$code" >> "$EVIDENCE_LOG"; echo "  evidence=$EVIDENCE_LOG (exit=$code)" >&2; fi' EXIT
}
run_gradle() {
  if [[ -n "${EVIDENCE_LOG:-}" ]]; then
    ./gradlew --console=plain "$@" 2>&1 | tee -a "$EVIDENCE_LOG"
  else
    ./gradlew --console=plain "$@"
  fi
}

if [[ "$TARGET" == "benchmark" || "$TARGET" == "godofredo-benchmark" ]]; then
  [[ "$PROFILE" != "store" ]] || fail "benchmark não é artefato de loja"
  echo "WA Keeper build"
  echo "  target=$TARGET"
  echo "  profile=lab"
  echo "  type=debug"
  echo "  defaults: target=app profile=lab type=debug mode=apk"
  (( PRINT_ONLY )) && exit 0
  start_evidence
  run_gradle ":$TARGET:assembleDebug"
  exit 0
fi

IFS=',' read -r -a ALL_FEATURES <<< "$(prop features)"
declare -A KNOWN=() ENABLED=()
for f in "${ALL_FEATURES[@]}"; do KNOWN["$f"]=1; done
validate_name(){ [[ -n "${KNOWN[$1]:-}" ]] || fail "feature desconhecida: $1"; }

if [[ -n "$ONLY" ]]; then
  IFS=',' read -r -a selected <<< "$ONLY"
  for f in "${selected[@]}"; do validate_name "$f"; ENABLED["$f"]=1; done
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
echo "  target=$TARGET"
echo "  profile=$PROFILE"
echo "  store=$STORE"
echo "  type=$BUILD_TYPE"
echo "  mode=$MODE"
echo "  features=${FEATURE_CSV:-<none>}"
echo "  defaults: target=app profile=lab type=debug mode=apk"

(( PRINT_ONLY )) && exit 0
start_evidence

ARGS=("-PwaStore=$STORE" "-PwaFeatures=$FEATURE_CSV")

if (( RUN_TESTS )); then
  run_gradle :app:testDebugUnitTest "${ARGS[@]}"
fi

if [[ "$MODE" == "bundle" ]]; then
  TASK="bundleRelease"
elif [[ "$BUILD_TYPE" == "debug" ]]; then
  TASK="assembleDebug"
else
  TASK="assembleRelease"
fi

run_gradle ":app:$TASK" "${ARGS[@]}"
