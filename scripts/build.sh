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
    --debug) BUILD_TYPE="debug"; MODE="apk" ;;
    --release) BUILD_TYPE="release"; MODE="apk" ;;
    --bundle) BUILD_TYPE="release"; MODE="bundle" ;;
    --skip-tests) RUN_TESTS=0 ;;
    --print) PRINT_ONLY=1 ;;
    -h|--help) echo "uso: bash scripts/build.sh [--lab|--store] [--feature N] [--no-feature N] [--only a,b] [--app|--benchmark] [--debug|--release|--bundle] [--skip-tests] [--print]"; exit 0 ;;
    *) fail "opção desconhecida: $1" ;;
  esac
  shift
done

if [[ "$TARGET" == "benchmark" ]]; then
  [[ "$PROFILE" != "store" ]] || fail "benchmark não é artefato de loja"
  echo "WA Keeper build"
  echo "  target=benchmark"
  echo "  profile=lab"
  echo "  type=debug"
  (( PRINT_ONLY )) && exit 0
  ./gradlew --console=plain :benchmark:assembleDebug
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
echo "  target=app"
echo "  profile=$PROFILE"
echo "  store=$STORE"
echo "  type=$BUILD_TYPE"
echo "  mode=$MODE"
echo "  features=${FEATURE_CSV:-<none>}"

(( PRINT_ONLY )) && exit 0

ARGS=("-PwaStore=$STORE" "-PwaFeatures=$FEATURE_CSV")

if (( RUN_TESTS )); then
  ./gradlew --console=plain testDebugUnitTest "${ARGS[@]}"
fi

if [[ "$MODE" == "bundle" ]]; then
  TASK="bundleRelease"
elif [[ "$BUILD_TYPE" == "debug" ]]; then
  TASK="assembleDebug"
else
  TASK="assembleRelease"
fi

./gradlew --console=plain "$TASK" "${ARGS[@]}"
