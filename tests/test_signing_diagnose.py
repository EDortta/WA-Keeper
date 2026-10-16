"""Signing check: read-only, fail-closed, and no APK deployment."""
import importlib.util
from pathlib import Path
from unittest import TestCase, mock
import io
import contextlib

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "signing-diagnose.py"
spec = importlib.util.spec_from_file_location("signing_diagnose", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class SigningTests(TestCase):
    def test_certificate_parser(self):
        raw = "Signer #1 certificate SHA-256 digest: 518fba5a5869316877f89805026d8660b15f915e7fd863ae632272a152217a99"
        with mock.patch.object(module, "call", return_value=mock.Mock(stdout=raw, returncode=0)):
            code, digests = module.cert("apksigner", "/tmp/sample.apk")
        self.assertEqual(code, 0)
        self.assertEqual(len(digests), 1)
        self.assertEqual(len(digests[0]), 64)

    def test_parser_rejects_missing_certificate(self):
        with mock.patch.object(module, "call", return_value=mock.Mock(stdout="not verified", returncode=1)):
            code, digests = module.cert("apksigner", "/tmp/sample.apk")
        self.assertEqual(code, 1)
        self.assertEqual(digests, [])

    def test_deploy_contains_preinstall_guard(self):
        script = (SCRIPT.parents[0] / "android-deploy.sh").read_text()
        self.assertIn('python3 scripts/signing-diagnose.py', script)
        self.assertLess(script.index('python3 scripts/signing-diagnose.py'),
                        script.index('log_line "==> Instalando sem apagar dados"'))

if __name__ == "__main__":
    import unittest
    unittest.main()
