#!/usr/bin/env bash
set -Eeuo pipefail

APP_ID="br.com.wanotifkeeper"
OUT_DIR="${WA_KEEPER_BACKUP_DIR:-$HOME/wa-keeper-backups}"
COMMAND="${1:-}"
BACKUP_INPUT="${2:-}"

usage() {
  cat <<'EOF'
WA-Keeper — backup seguro do banco/dados locais via ADB

Uso:
  bash scripts/android-data-backup.sh backup
  bash scripts/android-data-backup.sh backup-remove
  bash scripts/android-data-backup.sh restore <arquivo-backup.tar.gz>

Comandos:
  backup         cria backup no computador e devolve o app à build release.
  backup-remove  cria e verifica o backup; só então desinstala o WA-Keeper.
  restore        reinstala uma build temporariamente debuggable, restaura os
                 dados, e por fim reinstala a build release.

Por que existe a build debug temporária?
  A release do WA-Keeper é non-debuggable e android:allowBackup=false.
  O script instala por cima uma build debug com o MESMO applicationId e a
  MESMA chave de assinatura histórica. Isso preserva os dados e habilita
  'run-as' somente durante a operação. Depois volta para release.

Observações:
  - O backup contém banco Room, SharedPreferences e arquivos privados.
  - Acessibilidade e Acesso a notificações são configurações especiais do
    Android e precisam ser reativadas manualmente após uma desinstalação.
  - O script nunca desinstala se a criação/verificação do backup falhar.
EOF
}

fail() {
  printf 'ERRO: %s\n' "$*" >&2
  exit 1
}

[[ "$COMMAND" =~ ^(backup|backup-remove|restore)$ ]] || { usage; exit 2; }

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || fail "execute dentro do repositório WA-Keeper"
cd "$REPO_ROOT"

command -v adb >/dev/null 2>&1 || fail "adb não encontrado"
command -v gzip >/dev/null 2>&1 || fail "gzip não encontrado"
command -v tar >/dev/null 2>&1 || fail "tar não encontrado"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum não encontrado"
[[ -x ./gradlew ]] || fail "./gradlew não encontrado/executável"

ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=( -s "$ANDROID_SERIAL" )
else
  mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case "${#DEVICES[@]}" in
    0) fail "nenhum Android autorizado conectado" ;;
    1) ADB+=( -s "${DEVICES[0]}" ) ;;
    *) fail "mais de um Android conectado; use ANDROID_SERIAL=<serial>" ;;
  esac
fi

device_has_app() {
  "${ADB[@]}" shell pm path "$APP_ID" 2>/dev/null | grep -q '^package:'
}

build_debug_bridge() {
  printf '==> Compilando build temporária de backup (debug)\n'
  ./gradlew --console=plain assembleDebug
  DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
  [[ -f "$DEBUG_APK" ]] || fail "APK debug não encontrado"

  printf '==> Instalando build temporária sem apagar dados\n'
  "${ADB[@]}" install -r "$DEBUG_APK" >/dev/null ||
    fail "não consegui instalar a build debug por cima da atual; não desinstale o app"

  "${ADB[@]}" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  "${ADB[@]}" shell run-as "$APP_ID" id >/dev/null 2>&1 ||
    fail "run-as não ficou disponível; backup abortado sem desinstalar"
}

restore_release() {
  printf '==> Reinstalando release non-debuggable\n'
  ./gradlew --console=plain assembleRelease
  local apk="app/build/outputs/apk/release/app-release.apk"
  [[ -f "$apk" ]] || fail "APK release não encontrado"
  "${ADB[@]}" install -r "$apk" >/dev/null ||
    fail "dados foram preservados, mas falhou a volta para release"
}

capture_runtime_grants() {
  local out="$1"
  : > "$out"
  local dump
  dump="$("${ADB[@]}" shell dumpsys package "$APP_ID" 2>/dev/null || true)"
  for perm in     android.permission.READ_CONTACTS     android.permission.RECORD_AUDIO     android.permission.POST_NOTIFICATIONS     android.permission.READ_EXTERNAL_STORAGE
  do
    if grep -F "$perm: granted=true" <<<"$dump" >/dev/null; then
      printf '%s\n' "$perm" >> "$out"
    fi
  done
}

create_backup() {
  device_has_app || fail "WA-Keeper não está instalado; não há dados locais para copiar"

  local stamp work final tmp_final
  stamp="$(date '+%Y-%m-%d-%H-%M-%S')"
  mkdir -p "$OUT_DIR"
  work="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-backup.XXXXXX")"
  trap "rm -rf '$work'" EXIT

  printf '==> Parando o app para fechar banco/WAL de forma consistente\n'
  "${ADB[@]}" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true

  build_debug_bridge

  printf '==> Registrando metadados do aparelho e permissões\n'
  {
    printf 'created_at=%s\n' "$(date -Iseconds)"
    printf 'package=%s\n' "$APP_ID"
    printf 'device=%s\n' "$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
    printf 'android=%s\n' "$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
    printf 'sdk=%s\n' "$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
    "${ADB[@]}" shell dumpsys package "$APP_ID" 2>/dev/null |
      grep -E 'versionName=|versionCode=' | head -n 2 | tr -d '\r' || true
  } > "$work/metadata.txt"

  capture_runtime_grants "$work/runtime-grants.txt"

  {
    printf 'enabled_accessibility_services=%s\n'       "$("${ADB[@]}" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
    printf 'enabled_notification_listeners=%s\n'       "$("${ADB[@]}" shell settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r')"
  } > "$work/special-access-before-uninstall.txt"

  printf '==> Copiando dados privados para o computador\n'
  if ! "${ADB[@]}" exec-out run-as "$APP_ID" sh -c '
      set --
      for p in databases shared_prefs files no_backup; do
        [ -e "$p" ] && set -- "$@" "$p"
      done
      [ "$#" -gt 0 ] || exit 44
      tar -cf - "$@"
    ' | gzip -9 > "$work/private-data.tar.gz"
  then
    fail "falhou a leitura dos dados privados; app permanece instalado"
  fi

  [[ -s "$work/private-data.tar.gz" ]] || fail "backup privado ficou vazio"

  printf '==> Verificando integridade do backup\n'
  gzip -t "$work/private-data.tar.gz" || fail "gzip inválido; app permanece instalado"
  gzip -dc "$work/private-data.tar.gz" | tar -tf - >/dev/null ||
    fail "tar inválido; app permanece instalado"

  (
    cd "$work"
    sha256sum private-data.tar.gz metadata.txt runtime-grants.txt       special-access-before-uninstall.txt > SHA256SUMS
  )

  final="$OUT_DIR/wa-keeper-backup-$stamp.tar.gz"
  tmp_final="$final.tmp"
  tar -czf "$tmp_final" -C "$work" .
  tar -tzf "$tmp_final" >/dev/null || fail "pacote final inválido"
  mv "$tmp_final" "$final"

  printf '==> Backup confirmado: %s\n' "$final"
  sha256sum "$final"

  if [[ "$COMMAND" == "backup-remove" ]]; then
    printf '==> Backup íntegro. Desinstalando WA-Keeper somente agora.\n'
    "${ADB[@]}" uninstall "$APP_ID" >/dev/null ||
      fail "backup está salvo, mas a desinstalação falhou"
    printf 'OK: backup salvo e WA-Keeper removido.\n'
    printf 'Para restaurar:\n  bash scripts/android-data-backup.sh restore %q\n' "$final"
  else
    restore_release
    printf 'OK: backup salvo; WA-Keeper continua instalado em release.\n'
  fi
}

restore_backup() {
  [[ -n "$BACKUP_INPUT" ]] || fail "informe o arquivo de backup"
  [[ -f "$BACKUP_INPUT" ]] || fail "backup não encontrado: $BACKUP_INPUT"

  local work
  work="$(mktemp -d "${TMPDIR:-/tmp}/wa-keeper-restore.XXXXXX")"
  trap "rm -rf '$work'" EXIT

  printf '==> Abrindo e verificando pacote de backup\n'
  tar -xzf "$BACKUP_INPUT" -C "$work"
  [[ -f "$work/private-data.tar.gz" ]] || fail "private-data.tar.gz ausente"
  [[ -f "$work/SHA256SUMS" ]] || fail "SHA256SUMS ausente"
  (
    cd "$work"
    sha256sum -c SHA256SUMS
  ) || fail "checksum do backup não confere"

  gzip -t "$work/private-data.tar.gz" || fail "dados privados corrompidos"
  gzip -dc "$work/private-data.tar.gz" | tar -tf - >/dev/null ||
    fail "tar privado corrompido"

  printf '==> Preparando app restaurável\n'
  if device_has_app; then
    printf 'AVISO: existe uma instalação atual. Os dados privados atuais serão substituídos.\n'
  fi
  build_debug_bridge

  printf '==> Restaurando banco, preferências e arquivos privados\n'
  "${ADB[@]}" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true

  if ! gzip -dc "$work/private-data.tar.gz" |
    "${ADB[@]}" exec-in run-as "$APP_ID" sh -c '
      rm -rf databases shared_prefs files no_backup
      mkdir -p databases shared_prefs files no_backup
      tar -xf -
    '
  then
    fail "falhou a restauração dos dados privados; release ainda não foi reinstalada"
  fi

  printf '==> Restaurando permissões runtime que estavam concedidas\n'
  if [[ -f "$work/runtime-grants.txt" ]]; then
    while IFS= read -r perm; do
      [[ -n "$perm" ]] || continue
      "${ADB[@]}" shell pm grant "$APP_ID" "$perm" >/dev/null 2>&1 || true
    done < "$work/runtime-grants.txt"
  fi

  restore_release

  printf '==> Abrindo WA-Keeper restaurado\n'
  "${ADB[@]}" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  "${ADB[@]}" shell am start -n "$APP_ID/.MainActivity" >/dev/null

  printf '\nOK: dados restaurados.\n'
  printf 'IMPORTANTE: após desinstalação, reative manualmente se necessário:\n'
  printf '  - Acessibilidade do WA Keeper\n'
  printf '  - Acesso a notificações do WA Keeper\n'
  printf 'O estado anterior desses acessos está registrado dentro do arquivo de backup.\n'
}

case "$COMMAND" in
  backup|backup-remove) create_backup ;;
  restore) restore_backup ;;
esac
