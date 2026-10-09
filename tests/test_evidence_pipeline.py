"""WA-Keeper producer contract. Git writes belong to central publisher."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from argparse import Namespace

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "evidence.py"
spec = importlib.util.spec_from_file_location("wa_evidence", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ProducerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name) / "state"
        self.state.mkdir()

    def args(self, remote=False):
        return Namespace(project="WA-Keeper", feature="conversations", branch="feature/x",
                         commit="a"*40, target="app", profile="lab", build_type="debug",
                         mode="apk", exit_code=0, duration=2, remote=remote,
                         phase="build", error_category="none")

    def test_producer_delegates_enqueue_without_git_write(self):
        with mock.patch.object(module, "STATE", self.state), \
             mock.patch.object(module, "central_publisher", return_value=self.state/"publisher.py"), \
             mock.patch.object(module.subprocess, "run") as call:
            call.return_value.returncode = 0
            self.assertEqual(module.record(self.args()), 0)
            argv = call.call_args.args[0]
            self.assertIn("enqueue", argv)
            envelope = json.loads(Path(argv[-1]).read_text()) if Path(argv[-1]).exists() else None
            self.assertNotIn("push", argv)
            self.assertNotIn("commit", argv)

    def test_remote_asks_central_publisher_to_flush(self):
        with mock.patch.object(module, "STATE", self.state), \
             mock.patch.object(module, "central_publisher", return_value=self.state/"publisher.py"), \
             mock.patch.object(module.subprocess, "run") as call:
            call.return_value.returncode = 0
            self.assertEqual(module.record(self.args(remote=True)), 0)
            self.assertIn("flush", call.call_args.args[0])

    def test_paths_are_normalized(self):
        self.assertEqual(module.safe("feature/conversations"), "feature-conversations")
        self.assertEqual(module.safe("../x"), "x")

if __name__ == "__main__":
    unittest.main()
