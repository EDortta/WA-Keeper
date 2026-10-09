#!/usr/bin/env python3
"""Publish allowlisted build metadata to a shared, private evidence repository.

Raw logs never leave the local machine. A single clone is shared among projects
and worktrees; flock serializes writers. Failed uploads remain in the outbox.
"""
import argparse
import datetime as dt
import fcntl
import json
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

def flush():
    chmod_private(STATE)
    repo = STATE/"repo"
    outbox = STATE/"outbox"
    chmod_private(outbox)
    stage = "lock"
    with (STATE/"publish.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            stage = "sync"
            if not (repo/".git").exists():
                git("clone", REMOTE, str(repo))
            else:
                git("fetch", "origin")
                branch = git("symbolic-ref", "--short", "HEAD", cwd=repo).stdout.strip()
                upstream = git("rev-parse", "--verify", f"refs/remotes/origin/{branch}", cwd=repo, check=False)
                if upstream.returncode == 0:
                    git("-c", "user.name=Development Evidence", "-c",
                        "user.email=development-evidence@local",
                        "merge", "--no-edit", f"origin/{branch}", cwd=repo)
            stage = "prepare"
            files = sorted(outbox.glob("*.json"))
            if not files:
                return 0
            for entry in files:
                payload = json.loads(entry.read_text())
                dest = repo/payload["relative_path"]
                dest.parent.mkdir(parents=True, exist_ok=True)
                rendered = json.dumps(payload["report"], indent=2, ensure_ascii=False)+"\n"
                if dest.exists():
                    if dest.read_text() != rendered:
                        raise RuntimeError(f"evidence collision: {dest}")
                    continue
                dest.write_text(rendered)
            git("add", "--", ".", cwd=repo)
            diff = git("diff", "--cached", "--quiet", cwd=repo, check=False)
            if diff.returncode == 1:
                git("-c", "user.name=Development Evidence", "-c",
                    "user.email=development-evidence@local", "commit",
                    "-m", f"evidence: publish {len(files)} execution(s)", cwd=repo)
            stage = "push"
            for attempt in range(4):
                pushed = git("push", "origin", "HEAD", cwd=repo, check=False)
                if pushed.returncode == 0:
                    break
                stage = "resync"
                git("fetch", "origin", cwd=repo)
                git("-c", "user.name=Development Evidence", "-c",
                    "user.email=development-evidence@local",
                    "merge", "--no-edit", f"origin/{branch}", cwd=repo)
                stage = "push"
            else:
                raise RuntimeError("push refused after four synchronization attempts")
            stage = "acknowledge"
            for entry in files:
                entry.unlink()
            return 0
        except Exception as exc:
            diagnostic = STATE / "last-publish-error.log"
            detail = str(exc)
            if isinstance(exc, subprocess.CalledProcessError):
                detail = f"command={exc.cmd!r} returncode={exc.returncode} stderr={exc.stderr!r} stdout={exc.stdout!r}"
            diagnostic.write_text(f"stage={stage}\nerror={detail}\n")
            diagnostic.chmod(0o600)
            print(f"evidence upload deferred at stage={stage}; local diagnostic: {diagnostic}", file=sys.stderr)
            return 1

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
    f=outbox/(uuid.uuid4().hex+".json")
    f.write_text(json.dumps({"relative_path": relative, "report":report},ensure_ascii=False))
    f.chmod(0o600)
    print(f"Evidence queued: {relative}")
    if args.remote:
        flush()
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
