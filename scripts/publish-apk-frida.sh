#!/usr/bin/env bash
set -Eeuo pipefail

PUBLIC_BASE="${WA_PUBLISH_URL:-https://frida.inovacaosistemas.com.br:8443}"
SSH_TARGET="${WA_PUBLISH_SSH:-esteban@frida.inovacaosistemas.com.br}"
SSH_PORT="${WA_PUBLISH_SSH_PORT:-2200}"
REMOTE_SUBDIR="${WA_PUBLISH_SUBDIR:-wa-keeper}"
RUN_TESTS=1
DRY_RUN=0

usage() {
  cat <<'EOF'
Compila e publica o APK release do WA-Keeper em frida:8443.

Uso:
  bash scripts/publish-apk-frida.sh [opções]

Opções:
  --skip-tests   não roda os testes unitários antes do build
  --dry-run      descobre/valida o webroot, mas não publica o APK
  -h, --help     mostra esta ajuda

Variáveis opcionais:
  WA_PUBLISH_URL=https://frida.inovacaosistemas.com.br:8443
  WA_PUBLISH_SSH=esteban@frida.inovacaosistemas.com.br
  WA_PUBLISH_SSH_PORT=2200
  WA_PUBLISH_SUBDIR=wa-keeper

O script:
  1. garante development atualizada;
  2. roda testes e assembleRelease;
  3. descobre o document root real que atende a porta 8443;
  4. comprova o root usando um arquivo-sonda HTTP;
  5. publica APK versionado + WA-Keeper-latest.apk + SHA-256;
  6. baixa o APK publicado e confere o SHA-256.
EOF
}

fail() {
  printf 'ERRO: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '==> %s\n' "$*"
}

while (($#)); do
  case "$1" in
    --skip-tests) RUN_TESTS=0 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "opção desconhecida: $1" ;;
  esac
  shift
done

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$REPO_ROOT"

[[ -x ./gradlew ]] || fail "./gradlew não encontrado ou não executável"
command -v ssh >/dev/null 2>&1 || fail "ssh não encontrado"
command -v scp >/dev/null 2>&1 || fail "scp não encontrado"
command -v curl >/dev/null 2>&1 || fail "curl não encontrado"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum não encontrado"

if [[ -n "$(git status --porcelain)" ]]; then
  fail "working tree tem alterações locais; commit/stash antes de publicar"
fi

log "Atualizando development"
git fetch origin development
git switch development
git pull --ff-only origin development

if (( RUN_TESTS )); then
  log "Rodando testes unitários"
  ./gradlew --console=plain testDebugUnitTest
fi

log "Compilando APK release"
./gradlew --console=plain assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || fail "APK não encontrado em $APK"

VERSION="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]+"([^"]+)".*/\1/p' app/build.gradle | head -n1)"
[[ -n "$VERSION" ]] || VERSION="unknown"

APK_SHA="$(sha256sum "$APK" | awk '{print $1}')"
VERSIONED_NAME="WA-Keeper-${VERSION}.apk"
LATEST_NAME="WA-Keeper-latest.apk"

SSH_OPTS=(-p "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=6 -o ConnectionAttempts=1 -o ServerAliveInterval=5 -o ServerAliveCountMax=1)
SCP_OPTS=(-P "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=6 -o ConnectionAttempts=1 -o ServerAliveInterval=5 -o ServerAliveCountMax=1)

choose_ssh_target() {
  log "Testando SSH em $SSH_TARGET:$SSH_PORT"
  if ! timeout 10 ssh "${SSH_OPTS[@]}" "$SSH_TARGET" true >/dev/null 2>&1; then
    fail "SSH para $SSH_TARGET:$SSH_PORT não respondeu em até 10s ou falhou na autenticação"
  fi
}

collect_candidates() {
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" 'bash -s' <<'REMOTE'
set -u

emit_dir() {
  local p="$1"
  [[ -n "$p" && "$p" = /* && -d "$p" ]] && printf '%s\n' "$p"
}

# Nginx: pega todos os roots carregados. A sonda HTTP elimina os vhosts errados.
if command -v nginx >/dev/null 2>&1; then
  {
    nginx -T 2>/dev/null ||
    sudo -n nginx -T 2>/dev/null ||
    true
  } | awk '
    /^[[:space:]]*root[[:space:]]+/ {
      p=$2
      sub(/;$/, "", p)
      if (p ~ /^\//) print p
    }
  '
fi

# Apache/httpd.
for base in /etc/apache2 /etc/httpd; do
  [[ -d "$base" ]] || continue
  grep -RhsE '^[[:space:]]*DocumentRoot[[:space:]]+' "$base" 2>/dev/null |
    sed -E 's/^[[:space:]]*DocumentRoot[[:space:]]+"?([^" ]+)"?.*$/\1/' || true
done

# Caddy.
for f in /etc/caddy/Caddyfile /etc/caddy/*.conf; do
  [[ -r "$f" ]] || continue
  awk '
    /^[[:space:]]*root[[:space:]]+/ {
      for (i=2; i<=NF; i++) {
        if ($i ~ /^\//) {
          gsub(/[{};]/, "", $i)
          print $i
        }
      }
    }
  ' "$f"
done

# Processo que escuta 8443: útil para python/node/servidores simples.
SS="$(ss -ltnp 2>/dev/null || sudo -n ss -ltnp 2>/dev/null || true)"
printf '%s\n' "$SS" |
  grep -E '[:.]8443[[:space:]]' |
  grep -oE 'pid=[0-9]+' |
  cut -d= -f2 |
  sort -u |
  while read -r pid; do
    [[ -n "$pid" ]] || continue
    cwd="$(readlink -f "/proc/$pid/cwd" 2>/dev/null || sudo -n readlink -f "/proc/$pid/cwd" 2>/dev/null || true)"
    emit_dir "$cwd"
  done

# Containers publicados na 8443: testa diretórios montados no host.
for engine in docker podman; do
  command -v "$engine" >/dev/null 2>&1 || continue
  {
    "$engine" ps --format '{{.ID}} {{.Ports}}' 2>/dev/null ||
    sudo -n "$engine" ps --format '{{.ID}} {{.Ports}}' 2>/dev/null ||
    true
  } | grep -E '8443->|:8443' | awk '{print $1}' | while read -r cid; do
    [[ -n "$cid" ]] || continue
    {
      "$engine" inspect --format '{{range .Mounts}}{{println .Source}}{{end}}' "$cid" 2>/dev/null ||
      sudo -n "$engine" inspect --format '{{range .Mounts}}{{println .Source}}{{end}}' "$cid" 2>/dev/null ||
      true
    }
  done
done

# Fallbacks comuns. Só serão aceitos se a sonda realmente aparecer na URL pública.
for p in /var/www/html /var/www /srv/www /srv/http /opt/www "$HOME/public_html"; do
  emit_dir "$p"
done
REMOTE
}

remote_write_probe() {
  local root="$1" probe="$2" token="$3"
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" bash -s -- "$root" "$probe" "$token" <<'REMOTE'
set -e
root="$1"
probe="$2"
token="$3"
dest="$root/$probe"

if printf '%s' "$token" > "$dest" 2>/dev/null; then
  chmod 0644 "$dest" 2>/dev/null || true
  exit 0
fi

printf '%s' "$token" | sudo -n tee "$dest" >/dev/null
sudo -n chmod 0644 "$dest"
REMOTE
}

remote_remove_probe() {
  local root="$1" probe="$2"
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" bash -s -- "$root" "$probe" <<'REMOTE' >/dev/null 2>&1 || true
root="$1"
probe="$2"
rm -f "$root/$probe" 2>/dev/null || sudo -n rm -f "$root/$probe" 2>/dev/null || true
REMOTE
}

discover_webroot() {
  local probe token root body
  probe=".wa-keeper-probe-$$-$(date +%s).txt"
  token="wa-keeper-probe-$$-$(date +%s)-$RANDOM"

  mapfile -t CANDIDATES < <(collect_candidates | awk 'NF && /^\// && !seen[$0]++')

  (("${#CANDIDATES[@]}" > 0)) ||
    fail "não encontrei nenhum candidato a document root em $SSH_TARGET"

  log "Testando ${#CANDIDATES[@]} candidato(s) de webroot com sonda HTTP" >&2

  for root in "${CANDIDATES[@]}"; do

    if ! remote_write_probe "$root" "$probe" "$token" >/dev/null 2>&1; then
      continue
    fi

    body="$(curl -kfsSL --max-time 7 "${PUBLIC_BASE%/}/$probe" 2>/dev/null || true)"
    remote_remove_probe "$root" "$probe"

    if [[ "$body" == "$token" ]]; then
      printf '%s\n' "$root"
      return 0
    fi
  done

  printf '\nCandidatos encontrados, mas nenhum respondeu à sonda em %s:\n' "$PUBLIC_BASE" >&2
  printf '  %s\n' "${CANDIDATES[@]}" >&2
  return 1
}

publish_files() {
  local root="$1"
  local tmp_remote="/tmp/wa-keeper-publish-$$"
  local remote_dir="$root/$REMOTE_SUBDIR"
  local sha_file="${VERSIONED_NAME}.sha256"

  log "Enviando APK para $SSH_TARGET"
  scp "${SCP_OPTS[@]}" "$APK" "$SSH_TARGET:$tmp_remote.apk" >/dev/null

  printf '%s  %s\n' "$APK_SHA" "$VERSIONED_NAME" > "/tmp/$sha_file"
  scp "${SCP_OPTS[@]}" "/tmp/$sha_file" "$SSH_TARGET:$tmp_remote.sha256" >/dev/null
  rm -f "/tmp/$sha_file"

  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" bash -s --     "$remote_dir" "$tmp_remote.apk" "$tmp_remote.sha256" "$VERSIONED_NAME" "$LATEST_NAME" "$sha_file" <<'REMOTE'
set -e
remote_dir="$1"
apk_tmp="$2"
sha_tmp="$3"
versioned="$4"
latest="$5"
sha_name="$6"

install_direct() {
  mkdir -p "$remote_dir"
  install -m 0644 "$apk_tmp" "$remote_dir/$versioned"
  install -m 0644 "$apk_tmp" "$remote_dir/$latest"
  install -m 0644 "$sha_tmp" "$remote_dir/$sha_name"
}

if ! install_direct 2>/dev/null; then
  sudo -n mkdir -p "$remote_dir"
  sudo -n install -m 0644 "$apk_tmp" "$remote_dir/$versioned"
  sudo -n install -m 0644 "$apk_tmp" "$remote_dir/$latest"
  sudo -n install -m 0644 "$sha_tmp" "$remote_dir/$sha_name"
fi

rm -f "$apk_tmp" "$sha_tmp" 2>/dev/null || true
REMOTE
}

verify_publication() {
  local url="${PUBLIC_BASE%/}/$REMOTE_SUBDIR/$LATEST_NAME"
  local tmp
  tmp="$(mktemp "${TMPDIR:-/tmp}/wa-keeper-published.XXXXXX.apk")"
  trap 'rm -f "$tmp"' RETURN

  log "Validando APK publicado"
  curl -kfsSL --max-time 60 "$url" -o "$tmp" ||
    fail "o arquivo foi copiado, mas não consegui baixá-lo em $url"

  local remote_sha
  remote_sha="$(sha256sum "$tmp" | awk '{print $1}')"
  [[ "$remote_sha" == "$APK_SHA" ]] ||
    fail "SHA-256 do APK publicado não confere"

  rm -f "$tmp"
  trap - RETURN
}

choose_ssh_target
log "SSH: $SSH_TARGET:$SSH_PORT"
log "URL pública: $PUBLIC_BASE"

WEBROOT="$(discover_webroot)" || fail "não consegui provar qual pasta atende $PUBLIC_BASE"

printf '\nWebroot confirmado: %s:%s\n' "$SSH_TARGET" "$WEBROOT"

if (( DRY_RUN )); then
  printf 'Dry-run concluído. Nenhum APK foi publicado.\n'
  exit 0
fi

publish_files "$WEBROOT"
verify_publication

printf '\nPUBLICAÇÃO OK\n'
printf 'Versão: %s\n' "$VERSION"
printf 'SHA-256: %s\n' "$APK_SHA"
printf 'APK estável: %s/%s/%s\n' "${PUBLIC_BASE%/}" "$REMOTE_SUBDIR" "$LATEST_NAME"
printf 'APK versionado: %s/%s/%s\n' "${PUBLIC_BASE%/}" "$REMOTE_SUBDIR" "$VERSIONED_NAME"
printf 'Pasta remota: %s:%s/%s\n' "$SSH_TARGET" "$WEBROOT" "$REMOTE_SUBDIR"
