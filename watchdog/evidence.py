"""Queue sanitized Watchdog scan summaries in Development Evidences schema 1."""
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

def _safe(value):
    return re.sub(r"[^a-zA-Z0-9._-]", "-", str(value).strip()).strip(".-")[:90] or "unknown"

def _git(root, *args):
    try:
        process=subprocess.run(["git", *args],cwd=root,capture_output=True,text=True,timeout=5)
        return process.stdout.strip() if process.returncode==0 else "unknown"
    except (OSError, subprocess.TimeoutExpired):
        return "unknown"

def record_scan(root, *, success, duration, documents=0, domains=0, features=0,
                error_category="none", stage="finished"):
    """Always queue locally; never publish source documents or exception strings."""
    now=dt.datetime.now(dt.timezone.utc)
    run_id=now.strftime("%H%M%S")+"-"+uuid.uuid4().hex[:8]
    root=Path(root)
    project=root.name
    branch=_git(root,"branch","--show-current")
    commit=_git(root,"rev-parse","HEAD")
    relative="/".join((_safe(project),"watchdog-markdown-scan",_safe(branch),
                       now.strftime("%Y-%m-%d"),run_id,"manifest.json"))
    report={
        "schema":1,"timestamp_utc":now.isoformat(),"project":project,
        "feature":"watchdog-markdown-scan","branch":branch,"commit":commit,
        "target":"watchdog","profile":"local","build_type":"scan",
        "mode":"markdown","phase":"scan","status":"success" if success else "failed",
        "exit_code":0 if success else 1,"duration_seconds":round(duration,3),
        "error_category":error_category if not success else "none",
        "log_local_only":True,
        "scan":{"documents":int(documents),"domains":int(domains),
                "features":int(features),"stage":stage},
        "run_id":run_id,
    }
    state=Path(os.environ.get("XDG_STATE_HOME",str(Path.home()/".local/state")))/"development-evidences"
    outbox=state/"outbox"
    outbox.mkdir(parents=True,exist_ok=True,mode=0o700)
    os.chmod(outbox,0o700)
    destination=outbox/(uuid.uuid4().hex+".json")
    with destination.open("x",encoding="utf-8") as stream:
        json.dump({"relative_path":relative,"report":report},stream,ensure_ascii=False)
    os.chmod(destination,0o600)
    return {"run_id":run_id,"queued":True,"relative_path":relative}
