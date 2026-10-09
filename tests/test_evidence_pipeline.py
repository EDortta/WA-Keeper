"""Offline, concurrent and interrupted-push checks for the evidence publisher."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "evidence.py"

def git(*args, cwd=None):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()

class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.remote = root / "remote.git"
        git("init", "--bare", "--initial-branch=main", str(self.remote))
        self.env = dict(os.environ, XDG_STATE_HOME=str(root / "state"))
        self.env["GIT_CONFIG_NOSYSTEM"] = "1"

    def invoke(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args],
                              env=self.env, capture_output=True, text=True)

    def record(self, project="WA-Keeper", remote=False):
        args = ["record", "--project", project, "--feature", "conversations",
                "--branch", "feature/sample", "--commit", "a" * 40, "--target", "app",
                "--profile", "lab", "--build-type", "debug", "--mode", "apk",
                "--exit-code", "0", "--duration", "2"]
        if remote:
            args.append("--remote")
        return self.invoke(*args)

    def outbox(self):
        return list((Path(self.env["XDG_STATE_HOME"]) / "development-evidences" / "outbox").glob("*.json"))

    def publish(self):
        # Override remote via a local harness that copies the actual publisher,
        # not the user's GitHub state.
        code = SCRIPT.read_text().replace(
            'REMOTE = "git@github.com:EDortta/development-evidences.git"',
            f"REMOTE = {str(self.remote)!r}")
        patched = Path(self.tmp.name) / "evidence-local.py"
        patched.write_text(code)
        return patched

    def flush(self):
        patched = self.publish()
        return subprocess.run([sys.executable, str(patched), "flush"],
                              env=self.env, capture_output=True, text=True)

    def test_offline_queue_and_reconnect(self):
        self.assertEqual(self.record().returncode, 0)
        self.assertEqual(len(self.outbox()), 1)
        result = self.flush()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.outbox())
        self.assertEqual(len(git("--git-dir", str(self.remote), "ls-tree", "-r",
                                 "--name-only", "HEAD").splitlines()), 1)

    def test_two_producers_keep_both_records(self):
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda project: self.record(project),
                                        ("WA-Keeper", "YB-Convenio")))
        self.assertTrue(all(x.returncode == 0 for x in results))
        self.assertEqual(len(self.outbox()), 2)
        result = self.flush()
        self.assertEqual(result.returncode, 0, result.stderr)
        tree = git("--git-dir", str(self.remote), "ls-tree", "-r", "--name-only", "HEAD")
        self.assertIn("WA-Keeper/", tree)
        self.assertIn("YB-Convenio/", tree)

    def test_replay_after_push_before_acknowledgement(self):
        self.record()
        self.assertEqual(self.flush().returncode, 0)
        # Re-create an outbox item representing the identical already-pushed record.
        published = git("--git-dir", str(self.remote), "ls-tree", "-r", "--name-only", "HEAD").splitlines()[0]
        content = git("--git-dir", str(self.remote), "show", "HEAD:" + published)
        outbox = Path(self.env["XDG_STATE_HOME"]) / "development-evidences" / "outbox"
        (outbox / "replay.json").write_text(json.dumps({
            "relative_path": published, "report": json.loads(content)}))
        before = git("--git-dir", str(self.remote), "rev-parse", "HEAD")
        result = self.flush()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.outbox())
        self.assertEqual(before, git("--git-dir", str(self.remote), "rev-parse", "HEAD"))

if __name__ == "__main__":
    unittest.main()
