"""Minimal read-only local HTTP service: no frameworks or external dependencies."""
import json
import mimetypes
import errno
import webbrowser
import secrets
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from .catalog import domains, features, markdown_tree
from .scanner import scan, cached, create_domain_template
from .evidence import record_scan


def handler_for(repo, update_token):
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
                    self.json(dict(name=repo.root.name, root=str(repo.root), head=repo.head(), branch=repo.branch(), update_token=update_token))
                elif url.path == "/api/commits":
                    self.json(dict(commits=repo.commits(), refs=repo.refs(), head=repo.head()))
                elif url.path == "/api/scan":
                    self.json(cached(repo.root) or dict(items=[],never_scanned=True))
                elif url.path == "/api/domains":
                    self.json(domains(repo, sha))
                elif url.path == "/api/features":
                    self.json(features(repo, sha))
                elif url.path == "/api/document":
                    path = query.get("path", [""])[0]
                    if (not path.endswith(".md") or path.startswith("/") or
                            ".." in Path(path).parts or "\\x00" in path):
                        raise ValueError("Invalid Markdown path")
                    if sha == repo.head():
                        target=(repo.root/path).resolve()
                        if not target.is_relative_to(repo.root) or target.suffix.lower() != ".md":
                            raise ValueError("Unsafe Markdown path")
                        source=target.read_text("utf-8")[:300_000]
                    else:
                        source = repo.read_at(sha, path)
                    self.json(dict(path=path, sha=sha, markdown=source, tree=markdown_tree(source)))
                elif url.path in ("/", "/index.html", "/app.js", "/markdown.js", "/style.css"):
                    filename = "index.html" if url.path == "/" else url.path.lstrip("/")
                    self.send(200, (static / filename).read_bytes(),
                              mimetypes.guess_type(filename)[0] or "application/octet-stream")
                else:
                    self.json(dict(error="Not found"), 404)
            except (ValueError, OSError, RuntimeError) as e:
                self.json(dict(error=str(e)), 400)

        def do_POST(self):
            action=urlsplit(self.path).path
            if action not in ("/api/update-restart","/api/scan","/api/domain-template"):
                self.json(dict(error="Unknown action"), 405)
                return
            # Require exact local Host/Origin and a session-specific secret header.
            port = self.server.server_port
            valid_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
            host = self.headers.get("Host", "")
            origin = self.headers.get("Origin")
            if (host not in valid_hosts or (origin and origin not in
                    {f"http://{h}" for h in valid_hosts}) or
                    not secrets.compare_digest(self.headers.get("X-Watchdog-Token", ""), update_token)):
                self.json(dict(error="Unauthorized local request"), 403)
                return
            if not self.server.update_lock.acquire(blocking=False):
                self.json(dict(error="Update already running"), 409)
                return
            try:
                if action == "/api/domain-template":
                    self.json(create_domain_template(repo.root))
                    return
                if action == "/api/scan":
                    started=time.monotonic()
                    result=None
                    try:
                        result=scan(repo.root)
                        result["evidence"]=record_scan(
                            repo.root,success=True,duration=time.monotonic()-started,
                            documents=result["documents"],
                            domains=sum(i["kind"]=="domain" for i in result["items"]),
                            features=sum(i["kind"]=="feature" for i in result["items"]))
                        self.json(result)
                    except Exception as exc:
                        category="configuration" if isinstance(exc,(PermissionError,FileNotFoundError)) else "other"
                        evidence=None
                        try:
                            evidence=record_scan(repo.root,success=False,
                                duration=time.monotonic()-started,
                                documents=(result or {}).get("documents",0),
                                error_category=category,stage="failed")
                        except Exception:
                            pass
                        self.json(dict(error="Scan falhou: "+type(exc).__name__,
                                       evidence=evidence),500)
                    return
                branch = repo.branch()
                if branch == "(detached)":
                    self.json(dict(error="Detached HEAD: checkout a branch before updating"), 409)
                    return
                result = subprocess.run(
                    ["git", "-c", "pull.rebase=false", "pull", "--ff-only"],
                    cwd=repo.root, text=True, capture_output=True, timeout=90, check=False
                )
                if result.returncode:
                    self.json(dict(error=(result.stderr or result.stdout).strip()[-2500:]), 409)
                    return
                head = repo.head()
                self.json(dict(ok=True, head=head, message="Git pull concluído. Reiniciando o Watchdog..."))
                self.wfile.flush()
                self.close_connection = True
                self.server.restart_requested = True
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            except subprocess.TimeoutExpired:
                self.json(dict(error="git pull exceeded 90 seconds"), 504)
            except Exception as exc:
                self.json(dict(error=str(exc)), 500)
            finally:
                self.server.update_lock.release()

    return Handler


def serve(repo, port=8765, open_browser=True):
    """Try the requested port first, then ask the OS for an available local port."""
    token = secrets.token_urlsafe(32)
    handler = handler_for(repo, token)
    try:
        server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    except OSError as exc:
        if exc.errno != errno.EADDRINUSE or port == 0:
            raise
        print(f"Porta {port} ocupada; escolhendo outra porta local.", flush=True)
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    server.update_lock = threading.Lock()
    server.restart_requested = False
    actual_port = server.server_port
    with server:
        url = f"http://127.0.0.1:{server.server_port}"
        print(f"Watchdog: {url} | {repo.root}", flush=True)
        if open_browser:
            try:
                webbrowser.open(url)
            except Exception as exc:
                print(f"Navegador não aberto automaticamente: {exc}", flush=True)
        server.serve_forever()
    return actual_port if server.restart_requested else None
