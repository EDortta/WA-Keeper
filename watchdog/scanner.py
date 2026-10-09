"""Deterministic Markdown discovery; no model, network or repo modifications."""
import hashlib
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

SKIP={".git",".gradle",".dart_tool","node_modules","build","dist",".venv","venv","coverage",".next"}
ROOT_HINTS={"domains","dominios","domínios","features","feature","funcionalidades"}
HEADING=re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")
EXPLICIT=re.compile(r"^(?:dom[ií]nio|domain|feature|funcionalidade)\s*[:：-]\s*(.+)$",re.I)
DOMAINS=re.compile(r"^(?:dom[ií]nios|domains|mapa de dom[ií]nios|domain map)$",re.I)
FEATURES=re.compile(r"^(?:features|funcionalidades|validated features|features validadas e protegidas)$",re.I)
STATUSES={"protected":"PROTECTED","protegido":"PROTECTED","implementado":"implementado","proposto":"proposto"}

def cache_file(root):
    # Workspace cache is deliberately outside tracked project documentation.
    output=os.popen("") if False else None
    return Path(root)/".git"/"watchdog"/"catalog.json"

def discover(root):
    root=Path(root).resolve()
    results=[]
    inspected=0
    for base,dirs,files in os.walk(root):
        dirs[:]=sorted(d for d in dirs if d not in SKIP and not d.startswith("."))
        for name in sorted(files):
            if not name.lower().endswith(".md"): continue
            path=Path(base)/name
            if path.is_symlink() or path.stat().st_size>1_000_000:continue
            rel=path.relative_to(root).as_posix()
            content=path.read_text("utf-8",errors="replace")
            inspected+=1
            heads=[]
            fenced=False
            for lineno,line in enumerate(content.splitlines(),1):
                if re.match(r"^\s*(`{3,}|~{3,})",line):
                    fenced=not fenced;continue
                if fenced:continue
                m=HEADING.match(line)
                if m: heads.append((len(m[1]),m[2].strip(),lineno))
            context=[]
            for level,title,line in heads:
                while context and context[-1][0]>=level:context.pop()
                parent=[name for _,name in context]
                explicit=EXPLICIT.match(title)
                kind=None;canonical=title;confidence="explicit"
                if explicit:
                    word=title.split(":",1)[0].split("-",1)[0].strip().lower()
                    kind="domain" if word in ("domínio","dominio","domain") else "feature"
                    canonical=explicit[1].strip()
                elif any(DOMAINS.match(p) for p in parent) and not DOMAINS.match(title):
                    kind="domain"
                elif any(FEATURES.match(p) for p in parent) and not FEATURES.match(title) and title.lower() not in {"protected","protegido","ainda não protegido","proposed","proposto"}:
                    kind="feature"
                elif len(Path(rel).parts)>1 and Path(rel).parts[-2].lower() in ROOT_HINTS and level==1:
                    kind="domain" if Path(rel).parts[-2].lower() in {"domains","dominios","domínios"} else "feature"
                if kind:
                    identity=f"{kind}:{rel}:{line}:{canonical}"
                    item_id=hashlib.sha256(identity.encode()).hexdigest()[:16]
                    status=next((STATUSES[p.lower()] for p in reversed(parent) if p.lower() in STATUSES),"documentado")
                    results.append({"id":item_id,"kind":kind,"name":canonical,"path":rel,"line":line,
                                    "level":level,"status":status,"source":"markdown","confidence":confidence})
                context.append((level,title))
    return {"items":results,"documents":inspected}

def scan(root):
    target=cache_file(root)
    previous=None
    if target.exists():
        try:previous=json.loads(target.read_text("utf-8"))
        except (ValueError,OSError):pass
    current=discover(root)
    old={(x["kind"],x["path"],x["name"]):x for x in (previous or {}).get("items",[])}
    new={(x["kind"],x["path"],x["name"]):x for x in current["items"]}
    added=[x for key,x in new.items() if key not in old]
    missing=[x for key,x in old.items() if key not in new]
    changed=[{"before":old[k],"after":new[k]} for k in old.keys()&new.keys()
             if any(old[k].get(p)!=new[k].get(p) for p in ("status","line"))]
    current.update({"scanned_at":datetime.now(timezone.utc).isoformat(),
                    "diff":{"added":added,"missing":missing,"changed":changed},
                    "previous_scan":(previous or {}).get("scanned_at")})
    target.parent.mkdir(parents=True,exist_ok=True)
    tmp=target.with_suffix(".tmp")
    tmp.write_text(json.dumps(current,ensure_ascii=False,indent=2),"utf-8")
    os.replace(tmp,target)
    return current

def cached(root):
    path=cache_file(root)
    if not path.exists():return None
    try:return json.loads(path.read_text("utf-8"))
    except (ValueError,OSError):return None
