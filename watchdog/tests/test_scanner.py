import tempfile
import unittest
from pathlib import Path
from watchdog.scanner import discover, scan, cached, create_domain_template

class ScannerTests(unittest.TestCase):
    def test_mixed_single_and_multiple_documents(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            (root/".git").mkdir()
            (root/"docs"/"domains").mkdir(parents=True)
            (root/"docs"/"domains"/"security.md").write_text(
                "# Segurança\n## Responsabilidades\n- controlar acesso\n",encoding="utf-8")
            (root/"docs"/"overview.md").write_text(
                "# Mapa de domínios\n## Captura\n### Responsabilidades\nTexto\n"
                "# Features\n## Envio programado\n",encoding="utf-8")
            result=scan(root)
            self.assertEqual({i["name"] for i in result["items"] if i["kind"]=="domain"},
                             {"Captura","Segurança"})
            self.assertEqual({i["name"] for i in result["items"] if i["kind"]=="feature"},
                             {"Envio programado"})
            self.assertEqual(len(result["diff"]["missing"]),0)
            (root/"docs"/"domains"/"security.md").unlink()
            result=scan(root)
            self.assertEqual([i["name"] for i in result["diff"]["missing"]],["Segurança"])
            self.assertEqual(len(cached(root)["items"]),2)

    def test_template_is_non_destructive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/".git").mkdir()
            self.assertTrue(create_domain_template(root)["created"])
            self.assertFalse(create_domain_template(root)["created"])
            self.assertEqual(discover(root)["items"],[])

if __name__=="__main__":
    unittest.main()
