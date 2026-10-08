import argparse
import json
from .gitrepo import Repository
from .catalog import domains, features
from .api import serve

def main():
    parser = argparse.ArgumentParser(description="Goals Kit Watchdog")
    parser.add_argument("--root", default=".", help="Git repository")
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("open", help="Serve local browser UI")
    p.add_argument("--port", type=int, default=8765)
    sub.add_parser("history", help="Print Git commits as JSON")
    sub.add_parser("map", help="Print domain candidates")
    sub.add_parser("features", help="Print validated features")
    args = parser.parse_args()
    repo = Repository(args.root)
    if args.command == "open":
        serve(repo, args.port)
    else:
        actions = {"history": repo.commits, "map": lambda: domains(repo, repo.head()),
                   "features": lambda: features(repo, repo.head())}
        print(json.dumps(actions[args.command](), ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
