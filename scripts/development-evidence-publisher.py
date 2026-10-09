#!/usr/bin/env python3
"""Single-shot, ordered, crash-recoverable evidence Git publisher.

All producers write immutable JSON envelopes to the shared outbox. This script
is the ONLY process allowed to write/commit/push to the evidence Git checkout.
A kernel flock is released automatically if the process dies.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid

STATE = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "development-evidences"
REMOTE = os.environ.get("DEVELOPMENT_EVIDENCES_REMOTE", "git@github.com:EDortta/development-evidences.git")


def run(*argv, cwd=None, check=True):
    result = subprocess.run(argv, cwd=cwd, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(f"{argv[0]} {argv[1]} failed ({result.returncode}): {result.stderr[-400:]}")
    return result


def private_dir(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


def enqueue(envelope):
    """Atomic outbox write. Does not acquire the Git writer lock."""
    for key in ("relative_path", "report"):
        if key not in envelope:
            raise ValueError("missing " + key)
    relative = Path(envelope["relative_path"])
    if relative.is_absolute() or ".." in relative.parts or not relative.parts or relative.suffix != ".json":
        raise ValueError("unsafe relative_path")
    private_dir(STATE)
    outbox = STATE / "outbox"
    private_dir(outbox)
    # UUID is only a tie-breaker; ordering is determined by a locked sequence below.
    with (STATE / "queue.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        sequence_file = STATE / "sequence"
        number = int(sequence_file.read_text()) + 1 if sequence_file.exists() else 1
        temp = sequence_file.with_suffix(".tmp")
        temp.write_text(str(number))
        os.replace(temp, sequence_file)
        name = f"{number:020d}-{uuid.uuid4().hex}.json"
        with tempfile.NamedTemporaryFile(dir=outbox, prefix=".pending-", delete=False, mode="w") as f:
            json.dump(envelope, f, ensure_ascii=False)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
            tmp_path = Path(f.name)
        os.chmod(tmp_path, 0o600)
        os.replace(tmp_path, outbox / name)
        # Commit data and directory entries to disk where supported.
        descriptor = os.open(outbox, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    print(f"queued={name}")


def publish():
    private_dir(STATE)
    outbox = STATE / "outbox"
    private_dir(outbox)
    with (STATE / "publish.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        checkout = STATE / "repo"
        if not (checkout / ".git").exists():
            run("git", "clone", REMOTE, str(checkout))
        branch = run("git", "symbolic-ref", "--short", "HEAD", cwd=checkout).stdout.strip()
        # No reset, clean, force or rebase. Keep local unpublished commits.
        items = sorted(outbox.glob("*.json"), key=lambda p: (0 if p.name[:1].isdigit() else 1, p.name))
        for entry in items:
            envelope = json.loads(entry.read_text())
            relative = Path(envelope["relative_path"])
            if relative.is_absolute() or ".." in relative.parts or relative.suffix != ".json":
                raise ValueError(f"unsafe path in {entry.name}")
            dest = checkout / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            content = json.dumps(envelope["report"], sort_keys=True, indent=2, ensure_ascii=False) + "\n"
            if dest.exists() and json.loads(dest.read_text()) != envelope["report"]:
                raise RuntimeError(f"conflicting evidence {relative}")
            if not dest.exists():
                with tempfile.NamedTemporaryFile(dir=dest.parent, prefix=".evidence-", mode="w", delete=False) as f:
                    f.write(content)
                    f.flush()
                    os.fsync(f.fileno())
                    tmp = f.name
                os.replace(tmp, dest)
            run("git", "add", "--", str(relative), cwd=checkout)
            staged = run("git", "diff", "--cached", "--quiet", cwd=checkout, check=False)
            if staged.returncode == 1:
                run("git", "-c", "user.name=Development Evidence", "-c",
                    "user.email=development-evidence@local", "commit", "-m",
                    f"evidence: {relative}", cwd=checkout)
            # ACK only after remote contains the record.
            published = False
            for attempt in range(5):
                run("git", "fetch", "origin", cwd=checkout)
                remote_branch = f"origin/{branch}"
                remote_ref = run("git", "rev-parse", "--verify", remote_branch, cwd=checkout, check=False)
                if remote_ref.returncode == 0:
                    run("git", "-c", "user.name=Development Evidence", "-c",
                        "user.email=development-evidence@local", "merge",
                        "--no-edit", remote_branch, cwd=checkout)
                pushed = run("git", "push", "origin", f"HEAD:refs/heads/{branch}", cwd=checkout, check=False)
                if pushed.returncode == 0:
                    published = True
                    break
                time.sleep(min(2 ** attempt, 8))
            if not published:
                raise RuntimeError(f"push failed for {entry.name}; entry retained")
            entry.unlink()
            print(f"published={relative}")
        return len(items)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    put = sub.add_parser("enqueue")
    put.add_argument("--file", required=True, help="JSON envelope with relative_path and report")
    sub.add_parser("flush")
    args = parser.parse_args()
    try:
        if args.action == "enqueue":
            enqueue(json.loads(Path(args.file).read_text()))
        else:
            print(f"processed={publish()}")
    except Exception as exc:
        print(f"evidence-publisher: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
