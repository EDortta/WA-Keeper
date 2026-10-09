"""Contract tests for scan evidence: no source text or paths in published payload."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from watchdog.evidence import record_scan

class EvidenceTests(unittest.TestCase):
    def test_success_and_failure_enqueue_distinct_sanitized_manifests(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/"sample-project";root.mkdir()
            with patch.dict(os.environ,{"XDG_STATE_HOME":temp}):
                one=record_scan(root,success=True,duration=1.2,documents=12,domains=3,features=4)
                two=record_scan(root,success=False,duration=.4,error_category="other")
            self.assertNotEqual(one["run_id"],two["run_id"])
            queued=list((Path(temp)/"development-evidences"/"outbox").glob("*.json"))
            self.assertEqual(len(queued),2)
            reports=[json.loads(path.read_text())["report"] for path in queued]
            self.assertEqual({r["status"] for r in reports},{"success","failed"})
            for report in reports:
                self.assertEqual(report["phase"],"scan")
                self.assertTrue(report["log_local_only"])
                self.assertEqual(report["project"],"sample-project")
                self.assertNotIn("source",report)
                self.assertNotIn("markdown",report)
                self.assertNotIn("exception",report)
if __name__=="__main__":
    unittest.main()
