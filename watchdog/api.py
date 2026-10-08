"""Minimal read-only local HTTP service: no frameworks or external dependencies."""
import json
import mimetypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from .catalog import domains, features, markdown_tree


def handler_for(repo):
    static = Path(__file__).parent / "web"

    class Handler(BaseHTTPRequestHandler):
        def send(self, status, body, kind="application/json; charset=utf-8"):
            data = body if isinstance(body, bytes) else body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(data)

        def json(self, data, status=200):
            self.send(status, json.dumps(data, ensure_ascii=False).encode("utf-8"))

        def do_GET(self):
            url = urlsplit(self.path)
            query = parse_qs(url.query)
            try:
                sha = query.get("sha", [repo.head()])[0]
                if not (len(sha) == 40 and all(c in "0123456789abcdef" for c in sha.lower())):
                    raise ValueError("Use a full commit SHA")
                if url.path == "/api/project":
                    self.json(dict(name=repo.root.name, root=str(repo.root), head=repo.head(), branch=repo.branch()))
                elif url.path == "/api/commits":
                    self.json(dict(commits=repo.commits(), refs=repo.refs(), head=repo.head()))
                elif url.path == "/api/domains":
                    self.json(domains(repo, sha))
                elif url.path == "/api/features":
                    self.json(features(repo, sha))
                elif url.path == "/api/document":
                    path = query.get("path", [""])[0]
                    if not path.startswith("docs/") or not path.endswith(".md"):
                        raise ValueError("Only Markdown files under docs/ are readable")
                    source = repo.read_at(sha, path)
                    self.json(dict(path=path, sha=sha, markdown=source, tree=markdown_tree(source)))
                elif url.path in ("/", "/index.html", "/app.js", "/style.css"):
                    filename = "index.html" if url.path == "/" else url.path.lstrip("/")
                    self.send(200, (static / filename).read_bytes(),
                              mimetypes.guess_type(filename)[0] or "application/octet-stream")
                else:
                    self.json(dict(error="Not found"), 404)
            except (ValueError, OSError, RuntimeError) as e:
                self.json(dict(error=str(e)), 400)

        def do_POST(self):
            self.json(dict(error="Read-only MVP"), 405)

    return Handler


def serve(repo, port=8765):
    with ThreadingHTTPServer(("127.0.0.1", port), handler_for(repo)) as server:
        print(f"Watchdog: http://127.0.0.1:{server.server_port} | {repo.root}", flush=True)
        server.serve_forever()
