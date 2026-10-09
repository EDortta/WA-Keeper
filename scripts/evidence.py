#!/usr/bin/env python3
"""Publish allowlisted build metadata to a shared, private evidence repository.

Raw logs never leave the local machine. A single clone is shared among projects
and worktrees; flock serializes writers. Failed uploads remain in the outbox.
"""
import argparse
import datetime as dt
import fcntl
import json
import tempfile
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

STATE = Path(os.environ.get("XDG_STATE_HOME", str(Path.home()/".local/state"))) / "development-evidences"
REMOTE = "git@github.com:EDortta/development-evidences.git"

def git(*args, cwd=None, check=True):
    return subprocess.run(["git", *args], cwd=cwd, check=check, capture_output=True, text=True)

def safe(s):
    s = re.sub(r"[^a-zA-Z0-9._-]", "-", s.strip())
    return s.strip(".-")[:90] or "unknown"

def chmod_private(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)

def central_publisher():
    """Bootstrap a shared read-only checkout for publisher.py under a short lock."""
    import fcntl
    private = STATE
    chmod_private(private)
    checkout = private / "repo"
    # Only bootstrap/update the public script under a lock. Never merge,
    # commit or push: those operations belong solely to publisher.py.
    with (private / "publish.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if not (checkout / ".git").exists():
            git("clone", REMOTE, str(checkout))
        else:
            git("fetch", "origin", cwd=checkout)
        source = git("show", "origin/main:publisher.py", cwd=checkout).stdout
        script = private / "publisher.py"
        temp = private / ".publisher.new"
        temp.write_text(source)
        temp.replace(script)
    return script


def flush():
    script = central_publisher()
    result = subprocess.run([sys.executable, str(script), "flush"])
    return result.returncode

def record(args):
    chmod_private(STATE)
    outbox=STATE/"outbox"
    chmod_private(outbox)
    now=dt.datetime.now(dt.timezone.utc)
    runid=now.strftime("%H%M%S")+"-"+uuid.uuid4().hex[:8]
    relative="/".join([safe(args.project), safe(args.feature), safe(args.branch),
                       now.strftime("%Y-%m-%d"), runid, "manifest.json"])
    report={
        "schema": 1, "timestamp_utc": now.isoformat(),
        "project": args.project, "feature": args.feature, "branch": args.branch,
        "commit": args.commit, "target": args.target, "profile": args.profile,
        "build_type": args.build_type, "mode": args.mode, "status": "success" if args.exit_code == 0 else "failed",
        "exit_code": args.exit_code, "duration_seconds": args.duration,
        "log_local_only": True,
        "phase": args.phase,
        "error_category": args.error_category,
    }
    envelope={"relative_path": relative, "report":report}
    # Producers only enqueue; never commit or push Git.
    script=central_publisher()
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False,
                                     dir=STATE, prefix=".envelope-", suffix=".json") as tmp:
        json.dump(envelope, tmp, ensure_ascii=False)
        temp_path=Path(tmp.name)
    try:
        result=subprocess.run([sys.executable, str(script), "enqueue", "--file", str(temp_path)])
        if result.returncode != 0:
            return result.returncode
    finally:
        temp_path.unlink(missing_ok=True)
    if args.remote:
        return flush()
    return 0

def main():
    ap=argparse.ArgumentParser()
    sp=ap.add_subparsers(dest="command",required=True)
    rec=sp.add_parser("record")
    for key in ("project","feature","branch","commit","target","profile","build-type","mode"):
        rec.add_argument("--"+key,required=True)
    rec.add_argument("--exit-code",required=True,type=int)
    rec.add_argument("--duration",required=True,type=int)
    rec.add_argument("--phase",choices=("build","deploy"),default="build")
    rec.add_argument("--error-category",choices=("none","build","signing","install","device","configuration","other"),default="none")
    rec.add_argument("--remote",action="store_true")
    sp.add_parser("flush")
    args=ap.parse_args()
    return record(args) if args.command=="record" else flush()

if __name__=="__main__":
    sys.exit(main())
