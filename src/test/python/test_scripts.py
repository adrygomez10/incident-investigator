"""Check CLI import safety and helpers without accessing real accounts."""

import hashlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
import urllib.request
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]


def _load(path):
    specification = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


class ScriptContracts(unittest.TestCase):
    """Protect explicit execution boundaries and generated provenance."""

    def test_imports_do_not_start_external_work(self):
        """Importing a CLI must not start processes or send HTTP requests."""
        with (
            mock.patch.object(
                subprocess, "check_output", side_effect=AssertionError
            ),
            mock.patch.object(subprocess, "run", side_effect=AssertionError),
            mock.patch.object(subprocess, "Popen", side_effect=AssertionError),
            mock.patch.object(
                urllib.request, "urlopen", side_effect=AssertionError
            ),
            mock.patch.object(Path, "mkdir", side_effect=AssertionError),
        ):
            investigate = _load(ROOT / "scripts/investigate.py")
            with mock.patch.dict(sys.modules, {"investigate": investigate}):
                for name in (
                    "evaluate_live",
                    "export_corpus",
                    "probe_official_mcp",
                    "smoke_container",
                ):
                    _load(ROOT / "scripts" / f"{name}.py")
            _load(ROOT / "src/test/resources/mcp_fixture.py")

    def test_live_evaluation_requires_explicit_confirmation(self):
        """The cost-bearing command rejects execution before any API call."""
        investigate = _load(ROOT / "scripts/investigate.py")
        with mock.patch.dict(sys.modules, {"investigate": investigate}):
            evaluation = _load(ROOT / "scripts/evaluate_live.py")
        with (
            mock.patch.object(sys, "argv", ["evaluate_live.py"]),
            mock.patch.object(evaluation, "run", side_effect=AssertionError),
            redirect_stderr(io.StringIO()),
            self.assertRaises(SystemExit) as failure,
        ):
            evaluation.main()
        self.assertEqual(2, failure.exception.code)

    def test_probe_performs_a_complete_stdio_handshake(self):
        """Complete a stdio handshake against a local fixture."""
        probe = _load(ROOT / "scripts/probe_official_mcp.py")
        result = probe.probe(
            [
                sys.executable,
                str(ROOT / "src/test/resources/mcp_fixture.py"),
                "github",
            ],
            {},
        )
        self.assertIn("get_commit", result["toolNames"])
        self.assertEqual("explicit-test-fixture", result["server"]["name"])

    def test_export_hashes_exact_versioned_bytes(self):
        """A controlled Git fixture produces verifiable byte fingerprints."""
        export = _load(ROOT / "scripts/export_corpus.py")
        revision = "a" * 40
        content = b"# Contract\ncustomerReference is optional.\n"

        def git_fixture(repository, *arguments):
            if arguments[0] == "rev-parse":
                return revision.encode() + b"\n"
            if arguments[0] == "ls-tree":
                return b"docs/components.md\nignored.txt\n"
            self.assertEqual(
                ("show", revision + ":docs/components.md"), arguments
            )
            return content

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "corpus"
            with mock.patch.object(export, "_git", side_effect=git_fixture):
                result = export.export_corpus(ROOT, "HEAD", output)
            manifest = json.loads(
                (output / "manifest.json").read_text("utf-8")
            )
            self.assertEqual(content, (output / "components.md").read_bytes())
            self.assertEqual(revision, manifest["revision"])
            self.assertEqual(
                hashlib.sha256(content).hexdigest(),
                manifest["files"]["components.md"],
            )
            self.assertEqual(1, result["files"])


if __name__ == "__main__":
    unittest.main()
