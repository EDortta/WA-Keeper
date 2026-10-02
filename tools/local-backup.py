#!/usr/bin/env python3
"""
Exporta mídias do WA-Keeper para uma árvore local preservando origem lógica.

Saída:
  local-backup/<phone-id>/<business|whatsapp>/<entity-tag>/YYYY-MM-DD-hh-mm-ss.<ext>

Fontes:
  - áudio: notifications.audioPath
  - documentos: notifications.documentPath, quando essa coluna existir

O script usa o SQLite do WA-Keeper para descobrir:
  packageName -> whatsapp/business
  sender -> conversa
  entity_links + memory_entities -> entity-tag
  timestamp -> nome do arquivo

Por padrão tenta obter o banco pelo adb via run-as. Em builds não-debuggable,
passe --db com uma cópia local do wanotif.db ou use um aparelho com adb root.

Exemplos:
  python3 tools/local-backup.py
  python3 tools/local-backup.py --serial R58M123ABC
  python3 tools/local-backup.py --db /tmp/wanotif.db --phone-id meu-s23
  python3 tools/local-backup.py --audio-only
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

APP_ID = "br.com.wanotifkeeper"
DB_NAME = "wanotif.db"

PKG_WHATSAPP = "com.whatsapp"
PKG_BUSINESS = "com.whatsapp.w4b"

EVIDENCE_FILE: Path | None = None


def init_evidence(root: Path) -> None:
    global EVIDENCE_FILE
    root.mkdir(parents=True, exist_ok=True)
    EVIDENCE_FILE = root / "latest.log"
    EVIDENCE_FILE.write_text(
        "# WA-Keeper local-backup evidence\n"
        f"started_at={dt.datetime.now().astimezone().isoformat(timespec='seconds')}\n",
        encoding="utf-8",
    )


def evidence(message: str) -> None:
    if EVIDENCE_FILE is None:
        return
    with EVIDENCE_FILE.open("a", encoding="utf-8") as fh:
        fh.write(message.replace("\n", " | ") + "\n")


def run(cmd: list[str], *, check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        check=check,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        text=False,
    )


def adb_base(serial: str | None) -> list[str]:
    return ["adb"] + (["-s", serial] if serial else [])


def adb_text(serial: str | None, *args: str) -> str:
    cp = run(adb_base(serial) + list(args))
    return cp.stdout.decode("utf-8", errors="replace").strip()


def detect_serial(serial: str | None) -> str:
    if serial:
        return serial
    devices = adb_text(None, "devices").splitlines()[1:]
    online = [line.split()[0] for line in devices if line.strip().endswith("\tdevice")]
    evidence(f"adb_online_devices={len(online)}")
    if len(online) == 1:
        evidence("adb_device_selection=single")
        return online[0]
    if not online:
        evidence("failure=no_adb_device")
        raise SystemExit("Nenhum aparelho adb conectado.")
    evidence("failure=multiple_adb_devices")
    raise SystemExit("Mais de um aparelho conectado. Use --serial.")


def safe_part(value: str, fallback: str = "_unassigned") -> str:
    value = value.strip()
    value = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', "_", value)
    value = re.sub(r"\s+", " ", value).strip(" .")
    return (value[:100] or fallback)


def account_dir(package_name: str) -> str:
    if package_name == PKG_BUSINESS:
        return "business"
    if package_name == PKG_WHATSAPP:
        return "whatsapp"
    return safe_part(package_name, "_unknown")


def timestamp_name(epoch_ms: int) -> str:
    ts = dt.datetime.fromtimestamp(epoch_ms / 1000)
    return ts.strftime("%Y-%m-%d-%H-%M-%S")


SQLITE_HEADER = b"SQLite format 3\x00"


def is_sqlite_bytes(data: bytes) -> bool:
    return len(data) >= len(SQLITE_HEADER) and data.startswith(SQLITE_HEADER)


def copy_db_from_adb(serial: str, dest: Path) -> None:
    remote_abs = f"/data/data/{APP_ID}/databases/{DB_NAME}"
    attempts: list[str] = []
    evidence("db_source=adb")

    # Caminho principal: run-as com caminho relativo ao data dir do app.
    cp = subprocess.run(
        adb_base(serial) + ["exec-out", "run-as", APP_ID, "cat", f"databases/{DB_NAME}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    evidence(f"db_attempt_run_as_relative_rc={cp.returncode}")
    evidence(f"db_attempt_run_as_relative_sqlite={is_sqlite_bytes(cp.stdout)}")
    if cp.returncode == 0 and is_sqlite_bytes(cp.stdout):
        dest.write_bytes(cp.stdout)
        evidence("db_access=run_as_relative")
        return
    attempts.append(
        "run-as: " +
        ((cp.stderr or cp.stdout or b"no output").decode("utf-8", errors="replace").strip()[:300])
    )

    # Segunda tentativa: caminho absoluto sob run-as.
    cp = subprocess.run(
        adb_base(serial) + ["exec-out", "run-as", APP_ID, "cat", remote_abs],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    evidence(f"db_attempt_run_as_absolute_rc={cp.returncode}")
    evidence(f"db_attempt_run_as_absolute_sqlite={is_sqlite_bytes(cp.stdout)}")
    if cp.returncode == 0 and is_sqlite_bytes(cp.stdout):
        dest.write_bytes(cp.stdout)
        evidence("db_access=run_as_absolute")
        return
    attempts.append(
        "run-as(abs): " +
        ((cp.stderr or cp.stdout or b"no output").decode("utf-8", errors="replace").strip()[:300])
    )

    # Fallback para aparelho/emulador com root.
    cp = subprocess.run(
        adb_base(serial) + ["exec-out", "su", "-c", f"cat {remote_abs}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    evidence(f"db_attempt_su_rc={cp.returncode}")
    evidence(f"db_attempt_su_sqlite={is_sqlite_bytes(cp.stdout)}")
    if cp.returncode == 0 and is_sqlite_bytes(cp.stdout):
        dest.write_bytes(cp.stdout)
        evidence("db_access=su")
        return
    attempts.append(
        "su: " +
        ((cp.stderr or cp.stdout or b"no output").decode("utf-8", errors="replace").strip()[:300])
    )

    raise SystemExit(
        "Não consegui obter um SQLite válido do WA-Keeper via adb.\\n"
        + "\\n".join("  - " + a for a in attempts)
        + "\\nUse --db /caminho/wanotif.db se você já tiver uma cópia local do banco."
    )


def table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}


def query_media(conn: sqlite3.Connection, include_audio: bool, include_docs: bool):
    notif_cols = table_columns(conn, "notifications")
    has_audio = "audioPath" in notif_cols
    doc_col = next(
        (name for name in ("documentPath", "filePath", "attachmentPath") if name in notif_cols),
        None,
    )

    if include_docs and not doc_col:
        print(
            "AVISO: este banco ainda não possui documentPath/filePath/attachmentPath; "
            "documentos não podem ser associados com segurança à conversa/entidade.",
            file=sys.stderr,
        )

    selects = []
    if include_audio and has_audio:
        selects.append(("audio", "audioPath"))
    if include_docs and doc_col:
        selects.append(("document", doc_col))

    for kind, path_col in selects:
        sql = f"""
            SELECT
                n.id,
                n.packageName,
                n.sender,
                n.timestamp,
                n.{path_col} AS mediaPath,
                COALESCE(me.name, n.sender, '_unassigned') AS entityTag
            FROM notifications n
            LEFT JOIN entity_links el
              ON el.packageName = n.packageName
             AND lower(el.sender) = lower(n.sender)
            LEFT JOIN memory_entities me
              ON me.id = el.entityId
            WHERE n.{path_col} IS NOT NULL
              AND trim(n.{path_col}) <> ''
            ORDER BY n.timestamp ASC, n.id ASC
        """
        for row in conn.execute(sql):
            yield kind, row


def pull_device_file(serial: str, remote: str, local: Path) -> bool:
    local.parent.mkdir(parents=True, exist_ok=True)

    # 1) Arquivo legível pelo adb shell diretamente.
    cp = subprocess.run(
        adb_base(serial) + ["pull", remote, str(local)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if cp.returncode == 0 and local.exists() and local.stat().st_size > 0:
        return True
    local.unlink(missing_ok=True)

    # 2) Arquivo privado do WA-Keeper via run-as.
    cp = subprocess.run(
        adb_base(serial) + ["exec-out", "run-as", APP_ID, "cat", remote],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if cp.returncode == 0 and cp.stdout:
        local.write_bytes(cp.stdout)
        return True
    local.unlink(missing_ok=True)

    # 3) Fallback root.
    cp = subprocess.run(
        adb_base(serial) + ["exec-out", "su", "-c", f"cat {remote}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if cp.returncode == 0 and cp.stdout:
        local.write_bytes(cp.stdout)
        return True

    local.unlink(missing_ok=True)
    return False


def local_source_copy(source: str, dest: Path) -> bool:
    src = Path(source)
    if not src.is_file():
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    return True


def destination_for(root: Path, phone_id: str, row, source_path: str) -> Path:
    _id, package_name, _sender, timestamp, _media_path, entity_tag = row
    ext = Path(source_path).suffix.lower() or ".bin"
    base = timestamp_name(int(timestamp))
    folder = root / safe_part(phone_id, "phone") / account_dir(package_name) / safe_part(entity_tag)
    candidate = folder / f"{base}{ext}"
    if candidate.exists():
        candidate = folder / f"{base}-{_id}{ext}"
    return candidate


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", help="serial adb")
    ap.add_argument("--phone-id", help="id usado na pasta; default = serial adb")
    ap.add_argument("--db", type=Path, help="cópia local de wanotif.db")
    ap.add_argument("--output", type=Path, default=Path("local-backup"))
    ap.add_argument("--audio-only", action="store_true")
    ap.add_argument("--documents-only", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument(
        "--evidence-dir",
        type=Path,
        default=Path("evidence/local-backup"),
        help="diretório para evidências técnicas versionáveis",
    )
    args = ap.parse_args()
    init_evidence(args.evidence_dir)
    evidence(f"python={sys.version.split()[0]}")
    evidence(f"dry_run={args.dry_run}")
    evidence(f"audio_only={args.audio_only}")
    evidence(f"documents_only={args.documents_only}")

    if args.audio_only and args.documents_only:
        ap.error("--audio-only e --documents-only são mutuamente exclusivos")

    include_audio = not args.documents_only
    include_docs = not args.audio_only

    serial = None
    if args.db is None:
        serial = detect_serial(args.serial)
    elif args.serial:
        serial = args.serial

    phone_id = args.phone_id or serial or "phone"

    with tempfile.TemporaryDirectory(prefix="wa-keeper-backup-") as tmp:
        db_path = args.db
        if db_path is None:
            db_path = Path(tmp) / DB_NAME
            copy_db_from_adb(serial, db_path)
        elif not db_path.is_file():
            evidence("failure=db_file_not_found")
            raise SystemExit(f"Banco não encontrado: {db_path}")
        else:
            evidence("db_source=local_file")

        header = db_path.read_bytes()[:16]
        evidence(f"db_header_valid={header == SQLITE_HEADER}")
        if header != SQLITE_HEADER:
            preview = db_path.read_bytes()[:200].decode("utf-8", errors="replace")
            evidence("failure=invalid_sqlite_header")
            raise SystemExit(
                f"O arquivo obtido não é um banco SQLite válido: {db_path}\\n"
                f"Primeiros bytes: {preview!r}"
            )

        conn = sqlite3.connect(str(db_path))
        conn.row_factory = sqlite3.Row

        copied = 0
        failed = 0
        seen = 0

        for kind, row in query_media(conn, include_audio, include_docs):
            seen += 1
            source = row["mediaPath"]
            dest = destination_for(args.output, phone_id, row, source)

            print(f"{kind:8} {source} -> {dest}")
            if args.dry_run:
                continue

            ok = False
            if serial:
                ok = pull_device_file(serial, source, dest)
            else:
                ok = local_source_copy(source, dest)

            if ok:
                copied += 1
            else:
                failed += 1
                print(f"ERRO: não foi possível copiar {source}", file=sys.stderr)

        print(f"Encontrados: {seen} | copiados: {copied} | falhas: {failed}")
        evidence(f"media_seen={seen}")
        evidence(f"media_copied={copied}")
        evidence(f"media_failed={failed}")
        if include_docs and not any(
            c in table_columns(conn, "notifications")
            for c in ("documentPath", "filePath", "attachmentPath")
        ):
            print(
                "Documentos: pendente de indexação no WA-Keeper. "
                "O script já passará a exportá-los automaticamente quando a coluna existir.",
                file=sys.stderr,
            )

    code = 1 if failed else 0
    evidence(f"exit_code={code}")
    evidence(f"finished_at={dt.datetime.now().astimezone().isoformat(timespec='seconds')}")
    return code


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit as exc:
        code = exc.code if isinstance(exc.code, int) else 1
        evidence(f"exit_code={code}")
        evidence(f"finished_at={dt.datetime.now().astimezone().isoformat(timespec='seconds')}")
        if EVIDENCE_FILE is not None:
            print(f"Evidência: {EVIDENCE_FILE}")
        raise
    except Exception as exc:
        evidence(f"unhandled_exception={type(exc).__name__}:{exc}")
        evidence("exit_code=1")
        evidence(f"finished_at={dt.datetime.now().astimezone().isoformat(timespec='seconds')}")
        if EVIDENCE_FILE is not None:
            print(f"Evidência: {EVIDENCE_FILE}", file=sys.stderr)
        raise
