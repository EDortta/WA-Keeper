"""Read-only, argument-safe Git adapter."""
import json
import subprocess
from pathlib import Path


class GitError(RuntimeError):
    pass


class Repository:
    def __init__(self, path):
        path = Path(path).resolve()
        self.root = Path(self._run_in(path, ["rev-parse", "--show-toplevel"])).resolve()

    @staticmethod
    def _run_in(cwd, args):
        result = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, timeout=20, check=False)
        if result.returncode:
            raise GitError(result.stderr.strip() or "Git command failed")
        return result.stdout.strip()

    def run(self, *args):
        return self._run_in(self.root, list(args))

    def head(self):
        return self.run("rev-parse", "HEAD")

    def branch(self):
        return self.run("branch", "--show-current") or "(detached)"

    def commits(self, limit=250):
        limit = max(1, min(int(limit), 1000))
        raw = self.run("log", "--all", "--topo-order", "--date-order", f"-{limit}",
                       "--pretty=format:%H%x1f%P%x1f%aI%x1f%cI%x1f%s%x1e")
        nodes = []
        for record in raw.split("\x1e"):
            cells = record.strip().split("\x1f")
            if len(cells) == 5:
                sha, parents, authored, committed, subject = cells
                nodes.append(dict(sha=sha, short=sha[:8], parents=parents.split(), authored=authored,
                                  committed=committed, subject=subject))
        return nodes

    def refs(self):
        raw = self.run("for-each-ref", "--format=%(refname:short)%09%(objectname)", "refs/heads", "refs/remotes")
        return [dict(name=parts[0], sha=parts[1]) for row in raw.splitlines()
                if len(parts := row.split("\t", 1)) == 2 and not parts[0].endswith("/HEAD")]

    def exists_at(self, rev, path):
        result = subprocess.run(["git", "cat-file", "-e", f"{rev}:{path}"], cwd=self.root,
                                capture_output=True, timeout=10, check=False)
        return result.returncode == 0

    def read_at(self, rev, path):
        if not path or path.startswith("/") or ".." in Path(path).parts or "\x00" in path:
            raise ValueError("Unsafe repository path")
        if not rev or not all(c in "0123456789abcdef" for c in rev.lower()) or len(rev) != 40:
            raise ValueError("Revision must be a full commit SHA")
        data = self.run("show", f"{rev}:{path}")
        return data[:300_000]
