"""Domain catalog is an explicit proposal, not proof of implemented behavior."""
from pathlib import Path

from .scanner import cached

def domains(repo, sha):
    if sha != repo.head():return []
    snapshot=cached(repo.root)
    active=[item for item in (snapshot or {}).get("items",[]) if item["kind"]=="domain"]
    missing=[item for item in (snapshot or {}).get("diff",{}).get("missing",[]) if item["kind"]=="domain"]
    return [dict(id=item["id"],domain=item["name"],purpose="Fonte: "+item["path"],
                 owns="",excludes="",inputs="",outputs="",invariants="",
                 status=("AUSENTE NO SCAN" if item in missing else item["status"]),path=item["path"],line=item["line"],
                 historicity="scan-local") for item in active+missing]

def features(repo, sha):
    if sha == repo.head():
        snapshot=cached(repo.root)
        if snapshot is not None:
            missing=[item for item in snapshot.get("diff",{}).get("missing",[]) if item["kind"]=="feature"]
            return [dict(id=item["id"],name=item["name"],status=("AUSENTE NO SCAN" if item in missing else item["status"]),
                         path=item["path"],line=item["line"],historicity="scan-local")
                    for item in [x for x in snapshot["items"] if x["kind"]=="feature"]+missing]
    # Historical fallback only: document is loaded from selected Git revision.
    path="docs/validated-features.md"
    if not repo.exists_at(sha,path):return []
    import re
    body=repo.read_at(sha,path)
    return [dict(id=f"historical-{i}",name=m.group(1).strip(),
                 status="PROTECTED",path=path,historicity="documented")
            for i,m in enumerate(re.finditer(r"^###\\s+(.+)$",body,re.M),1)]

def markdown_tree(source):
    """Balanced heading hierarchy without losing original Markdown source."""
    import re
    root = {"title": "Documento", "level": 0, "children": [], "body": ""}
    stack = [root]
    for line in source.splitlines():
        m = re.match(r"^(#{1,6})\s+(.+)$", line)
        if m:
            level = len(m.group(1))
            while len(stack) > 1 and stack[-1]["level"] >= level:
                stack.pop()
            node = {"title": m.group(2), "level": level, "children": [], "body": ""}
            stack[-1]["children"].append(node)
            stack.append(node)
        else:
            stack[-1]["body"] += line + "\n"
    return root
