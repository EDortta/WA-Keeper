#!/usr/bin/env bash
set -Eeuo pipefail

command -v keytool >/dev/null 2>&1 || { echo 'ERRO: keytool não encontrado' >&2; exit 1; }
ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo 'ERRO: rode dentro do repo' >&2; exit 1; }
DIR="${HOME}/.config/wa-keeper/signing"
KEYSTORE="$DIR/wa-keeper-play.jks"
ALIAS="wa-keeper-play"

mkdir -p "$DIR"
chmod 700 "$DIR"

if [[ -e "$KEYSTORE" ]]; then
  echo "ERRO: $KEYSTORE já existe; não será sobrescrito." >&2
  exit 1
fi

read -rsp 'Senha do keystore: ' STORE_PASS; echo
read -rsp 'Repita a senha do keystore: ' STORE_PASS2; echo
[[ "$STORE_PASS" == "$STORE_PASS2" ]] || { echo 'ERRO: senhas diferentes' >&2; exit 1; }
read -rsp 'Senha da chave: ' KEY_PASS; echo

keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 -storepass "$STORE_PASS" -keypass "$KEY_PASS"
chmod 600 "$KEYSTORE"

cat > "$ROOT/release.properties" <<EOF
EOF
chmod 600 "$ROOT/release.properties"

unset STORE_PASS STORE_PASS2 KEY_PASS
echo "OK: assinatura local criada fora do Git e release.properties local configurado."
echo "IMPORTANTE: faça backup seguro do keystore; perdê-lo pode impedir futuras atualizações fora do Play App Signing."
