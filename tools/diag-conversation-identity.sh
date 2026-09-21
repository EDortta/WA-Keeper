#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE_ROOT="$ROOT/evidence/conversation-identity"
TS="$(date +%Y-%m-%d-%H-%M)"
OUT="$EVIDENCE_ROOT/$TS"
RAW="$OUT/notifications-raw.txt"
SUMMARY="$OUT/notifications-summary.txt"
DEVICE="$OUT/device.txt"
README="$OUT/README.md"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
require_cmd() { command -v "$1" >/dev/null 2>&1 || fail "comando obrigatório não encontrado: $1"; }

require_cmd adb
mkdir -p "$OUT"

mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ ${#DEVICES[@]} -ne 1 ]]; then
  adb devices >&2 || true
  fail "esperado exatamente 1 Android em estado 'device', encontrado(s): ${#DEVICES[@]}"
fi
ADB=(adb -s "${DEVICES[0]}")

log "gravando evidências em $OUT"

{
  echo "# Device"
  echo "capturedAt=$(date -Iseconds)"
  echo "serial=${DEVICES[0]}"
  echo
  echo "## adb"
  adb version || true
  echo
  echo "## getprop selected"
  for p in ro.product.manufacturer ro.product.brand ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.build.fingerprint; do
    printf '%s=' "$p"
    "${ADB[@]}" shell getprop "$p" | tr -d '\r'
  done
  echo
  echo "## packages"
  for pkg in com.whatsapp com.whatsapp.w4b br.com.wanotifkeeper; do
    echo "### $pkg"
    if "${ADB[@]}" shell pm path "$pkg" >/dev/null 2>&1; then
      "${ADB[@]}" shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r' | grep -E '^[[:space:]]*(versionName=|versionCode=|firstInstallTime=|lastUpdateTime=|pkg=|userId=)' || true
    else
      echo "not-installed"
    fi
    echo
  done
} > "$DEVICE"

log "capturando dumpsys notification --noredact"
{
  echo "# dumpsys notification --noredact"
  echo "capturedAt=$(date -Iseconds)"
  echo "serial=${DEVICES[0]}"
  echo
  "${ADB[@]}" shell dumpsys notification --noredact 2>&1 | tr -d '\r'
} > "$RAW"

log "gerando resumo técnico"
python3 - "$RAW" "$SUMMARY" <<'PY'
import re
import sys
from pathlib import Path
raw_path = Path(sys.argv[1])
out_path = Path(sys.argv[2])
text = raw_path.read_text(errors="replace")
lines = text.splitlines()
packages = ("com.whatsapp", "com.whatsapp.w4b")
key_re = re.compile(r"key=([^\s]+)")
field_patterns = [
    ("notification key", re.compile(r"\bkey=([^\s]+)")),
    ("tag", re.compile(r"\btag=([^\s]+)")),
    ("groupKey", re.compile(r"\b(?:groupKey|group)=([^\s]+)")),
    ("shortcutId", re.compile(r"\bshortcutId=([^\s]+)")),
    ("channelId", re.compile(r"\b(?:channelId|mChannelId)=([^\s]+)")),
    ("category", re.compile(r"\bcategory=([^\s]+)")),
    ("when", re.compile(r"\bwhen=([^\n]+)")),
    ("postTime", re.compile(r"\bpostTime=([^\n]+)")),
]
extra_names = [
    "android.title", "android.text", "android.bigText", "android.conversationTitle",
    "android.isGroupConversation", "android.messages", "android.people.list",
    "android.messagingUser", "android.selfDisplayName", "android.template",
    "android.subText", "android.summaryText", "android.hiddenConversationTitle",
]
additional_needles = ["conversation", "shortcut", "locus", "people", "person", "sender", "MessagingStyle", "mUser", "mConversation"]
blocks = []
for i, line in enumerate(lines):
    if any(pkg in line for pkg in packages) and ("NotificationRecord" in line or "StatusBarNotification" in line or "pkg=" in line or "key=" in line):
        start = max(0, i - 8)
        end = min(len(lines), i + 95)
        block_lines = lines[start:end]
        block = "\n".join(block_lines)
        if any(pkg in block for pkg in packages):
            blocks.append((i + 1, block_lines))
seen = set()
unique = []
for line_no, block_lines in blocks:
    block = "\n".join(block_lines)
    m = key_re.search(block)
    ident = m.group(1) if m else f"line:{line_no}"
    if ident in seen:
        continue
    seen.add(ident)
    unique.append((line_no, block_lines))
with out_path.open("w") as out:
    out.write("# WhatsApp notification identity summary\n")
    out.write(f"raw={raw_path}\n")
    out.write(f"candidate_blocks={len(unique)}\n\n")
    if not unique:
        out.write("Nenhum bloco de notificação de com.whatsapp/com.whatsapp.w4b foi encontrado.\n")
        out.write("Abra/receba mensagens nas conversas duplicadas e execute novamente.\n")
    for idx, (line_no, block_lines) in enumerate(unique, 1):
        block = "\n".join(block_lines)
        pkg = next((pkg for pkg in packages if pkg in block), "unknown")
        out.write(f"## candidate {idx} (raw line ~{line_no})\n")
        out.write(f"package: {pkg}\n")
        for label, pat in field_patterns:
            values = []
            for m in pat.finditer(block):
                v = m.group(1).strip().strip(',')
                if v and v not in values:
                    values.append(v)
            if values:
                out.write(f"{label}: {' | '.join(values[:8])}\n")
        out.write("\n### extras and conversation-related lines\n")
        interesting = []
        for l in block_lines:
            s = l.strip()
            if any(name in s for name in extra_names) or any(n.lower() in s.lower() for n in additional_needles):
                if s not in interesting:
                    interesting.append(s)
        if interesting:
            for s in interesting[:80]:
                out.write(f"- {s}\n")
        else:
            out.write("- no focused lines found in context window\n")
        out.write("\n### raw excerpt\n```\n")
        out.write("\n".join(block_lines[:95]))
        out.write("\n```\n\n")
PY

cat > "$README" <<EOF_README
# Issue #56 — diagnóstico de identidade de conversas WhatsApp

Captura gerada em: $(date -Iseconds)
Dispositivo: ${DEVICES[0]}

## Arquivos

- \`device.txt\`: modelo, Android, versão dos pacotes WhatsApp/Business e WA Keeper.
- \`notifications-raw.txt\`: saída bruta de \`adb shell dumpsys notification --noredact\`.
- \`notifications-summary.txt\`: blocos candidatos de \`com.whatsapp\` e \`com.whatsapp.w4b\`, com linhas técnicas relevantes.

## Como interpretar

Compare os blocos de \`notifications-summary.txt\` das conversas que aparecem duplicadas no WA Keeper.
O campo que deve virar identidade estável é o que permanece igual para notificações da mesma conversa e muda entre conversas diferentes.

Prioridade de campos a validar contra a evidência real:

1. \`shortcutId\` / linhas contendo \`shortcut\`;
2. identificadores de \`Person\`, \`people.list\`, \`locus\` ou campos com \`conversation\`;
3. \`groupKey\` somente se diferenciar conversas reais sem colidir com resumos;
4. \`tag\` e \`notification key\` apenas se a evidência mostrar estabilidade entre reposts da mesma conversa;
5. título normalizado deve ser fallback, não fonte principal de verdade.
EOF_README

log "ok"
echo "$OUT"
