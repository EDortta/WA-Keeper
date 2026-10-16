import tempfile
import subprocess
import unittest
from pathlib import Path
from watchdog.gitrepo import Repository
from watchdog.catalog import markdown_tree


class RepositoryTests(unittest.TestCase):
    def test_history_and_revision_document(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            def git(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout.strip()
            git("init", "-q")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@example.invalid")
            (root / "docs").mkdir()
            (root / "docs" / "validated-features.md").write_text("# Features\n## PROTECTED\n### Audio\nPreserve playback.\n", encoding="utf-8")
            git("add", ".")
            git("commit", "-qm", "baseline")
            repo = Repository(root)
            sha = repo.head()
            self.assertEqual(len(repo.commits()), 1)
            self.assertEqual(repo.commits()[0]["sha"], sha)
            self.assertTrue(repo.exists_at(sha, "docs/validated-features.md"))
            self.assertIn("Audio", repo.read_at(sha, "docs/validated-features.md"))
            with self.assertRaises(ValueError):
                repo.read_at(sha, "../secret.txt")

    def test_markdown_tree_hierarchy(self):
        tree = markdown_tree("# Features\n## PROTECTED\n### Audio\nText\n### Backup\n")
        features = tree["children"][0]
        self.assertEqual(features["children"][0]["title"], "PROTECTED")
        self.assertEqual([n["title"] for n in features["children"][0]["children"]], ["Audio", "Backup"])


if __name__ == "__main__":
    unittest.main()
