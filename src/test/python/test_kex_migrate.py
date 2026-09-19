# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("kex_migrate", Path(__file__).parents[3] / "bin/kex_migrate.py")
migration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(migration)


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "openclaw"
        self.source.mkdir()
        self.skill = self.source / "workspace/skills/check/SKILL.md"
        self.skill.parent.mkdir(parents=True)
        self.skill.write_text("---\nname: check\napiKey: frontmatter-secret\n---\nCheck lag. token-in-prose\n")
        (self.source / "openclaw.json").write_text(json.dumps({"models": {"apiKey": "token-in-prose"}, "channels": {"slack": {"enabled": True}}}))

    def tearDown(self):
        self.temp.cleanup()

    def test_secrets_removed_and_pending_only(self):
        bundle = migration.plan(self.source)
        serialized = json.dumps(bundle)
        self.assertNotIn("frontmatter-secret", serialized)
        self.assertNotIn("token-in-prose", serialized)
        self.assertEqual(bundle["skills"][0]["status"], "PENDING_REVIEW")
        self.assertIn("channels.slack.enabled", bundle["unsupportedFields"])
        self.assertIn("token-in-prose", self.skill.read_text())

    def test_dry_run_creates_nothing_and_existing_output_is_preserved(self):
        with patch("sys.stdout"):
            self.assertEqual(migration.main(["--source", str(self.source)]), 0)
        self.assertEqual(set(p.name for p in self.root.iterdir()), {"openclaw"})
        output = self.root / "migration"
        bundle = migration.plan(self.source)
        migration.write_bundle(bundle, output)
        before = (output / "pending-skills.json").read_bytes()
        with self.assertRaises(FileExistsError):
            migration.write_bundle(bundle, output)
        self.assertEqual((output / "pending-skills.json").read_bytes(), before)

    def test_rejects_symlink_skills_and_json5_without_leaking_snippet(self):
        self.skill.unlink()
        self.skill.symlink_to(self.source / "openclaw.json")
        with self.assertRaises(ValueError):
            migration.plan(self.source)
        (self.source / "openclaw.json").write_text('{apiKey:"do-not-display"}')
        with self.assertRaisesRegex(ValueError, "unsupported JSON5") as error:
            migration.plan(self.source)
        self.assertNotIn("do-not-display", str(error.exception))

    def test_refuses_external_cleartext_api(self):
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            migration.import_pending_skills(migration.plan(self.source), "http://example.org")

    def test_api_cannot_import_approval_and_uses_expected_contract(self):
        from io import BytesIO
        opener = unittest.mock.MagicMock()
        opener.open.return_value.__enter__.return_value = BytesIO(b'{"id":"test","status":"PENDING"}')
        with patch.dict("os.environ", {"KEX_API_TOKEN": "test-token"}), patch("urllib.request.build_opener", return_value=opener):
            self.assertEqual(migration.import_pending_skills(migration.plan(self.source), "http://localhost:8080"), ["test"])
        request = opener.open.call_args.args[0]
        body = json.loads(request.data)
        self.assertEqual(set(body), {"title", "markdown", "evidence"})
        self.assertEqual(request.full_url, "http://localhost:8080/api/agent/skills")


if __name__ == "__main__":
    unittest.main()
