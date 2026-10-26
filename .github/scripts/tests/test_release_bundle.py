"""Exercise the release bundle CLI used between build and publishing jobs."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import importlib.util

spec = importlib.util.spec_from_file_location("release_bundle", Path(__file__).resolve().parents[1] / "release_bundle.py")


SCRIPT = Path(__file__).resolve().parents[1] / "release_bundle.py"


class ReleaseBundleTest(unittest.TestCase):
    def test_stage_preserves_the_built_bytes_and_composed_notes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "ae2webintegration-2.0.0-neoforge-1.21.1.jar"
            artifact.write_bytes(b"immutable build output")
            notes = root / "notes.md"
            notes.write_text("## Notice\n\nRelease introduction.\n\n## Changes\n\nFixes.\n", encoding="utf-8")
            generator = root / "ae2webintegration-icon-generator-2.0.0-neoforge-1.21.1.jar"
            generator.write_bytes(b"separate client tool")
            sources = root / "ae2webintegration-2.0.0-neoforge-1.21.1-sources.jar"
            sources.write_bytes(b"source archive")
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "artifact": str(artifact), "sha512": hashlib.sha512(artifact.read_bytes()).hexdigest(),
                "sha1": hashlib.sha1(artifact.read_bytes()).hexdigest(),
                "tag": "2.0.0-neoforge-1.21.1", "minecraft": "1.21.1", "loader": "neoforge",
                "release_type": "release", "notes_file": str(notes),
                "additional_artifacts": [
                    {"artifact": str(generator), "role": "icon-generator",
                     "sha1": hashlib.sha1(generator.read_bytes()).hexdigest(),
                     "sha512": hashlib.sha512(generator.read_bytes()).hexdigest()},
                    {"artifact": str(sources), "role": "sources",
                     "sha1": hashlib.sha1(sources.read_bytes()).hexdigest(),
                     "sha512": hashlib.sha512(sources.read_bytes()).hexdigest()},
                ],
            }), encoding="utf-8")
            metadata = root / "metadata.json"
            metadata.write_text(json.dumps({
                "tag": "2.0.0-neoforge-1.21.1", "minecraft": "1.21.1", "loader": "neoforge",
                "release_type": "release", "prerelease": False, "snapshot": False,
            }), encoding="utf-8")
            bundle = root / "bundle"
            result = subprocess.run([
                sys.executable, str(SCRIPT), "stage", "--manifest", str(manifest),
                "--metadata", str(metadata), "--output", str(bundle),
            ], capture_output=True, text=True, encoding="utf-8")
            self.assertEqual(result.returncode, 0, result.stderr)
            staged = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual((bundle / staged["artifact"]).read_bytes(), b"immutable build output")
            self.assertEqual((bundle / staged["notes_file"]).read_text(encoding="utf-8"),
                             notes.read_text(encoding="utf-8"))
            self.assertFalse(Path(staged["artifact"]).is_absolute())
            self.assertEqual((bundle / staged["additional_artifacts"][0]["artifact"]).read_bytes(), b"separate client tool")
            self.assertEqual((bundle / staged["additional_artifacts"][1]["artifact"]).read_bytes(), b"source archive")
            self.assertEqual(len(list(bundle.glob("*.jar"))), 3)
            job_output = root / "job-output"
            exported = subprocess.run([
                sys.executable, str(SCRIPT), "export", "--bundle", str(bundle),
                "--github-output", str(job_output),
            ], capture_output=True, text=True, encoding="utf-8")
            self.assertEqual(exported.returncode, 0, exported.stderr)
            outputs = dict(line.split("=", 1) for line in job_output.read_text(encoding="utf-8").splitlines())
            self.assertEqual(Path(outputs["artifact"]).read_bytes(), b"immutable build output")
            self.assertEqual(outputs["sha512"], staged["sha512"])

    def test_github_preview_validates_bundle_without_contacting_github(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = b"same production bytes"
            (root / "ae2webintegration-2.0.0-forge-1.7.10.jar").write_bytes(data)
            (root / "notes.md").write_text("A notice and generated changes.\n", encoding="utf-8")
            (root / "manifest.json").write_text(json.dumps({
                "artifact": "ae2webintegration-2.0.0-forge-1.7.10.jar", "notes_file": "notes.md",
                "sha512": hashlib.sha512(data).hexdigest(), "sha1": hashlib.sha1(data).hexdigest(),
                "tag": "2.0.0-forge-1.7.10", "release_type": "release",
            }), encoding="utf-8")
            (root / "metadata.json").write_text(json.dumps({
                "tag": "2.0.0-forge-1.7.10", "prerelease": False,
            }), encoding="utf-8")
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            # The process boundary is GitHub CLI, not a mocked publishing implementation.
            with patch("subprocess.run", side_effect=AssertionError("Preview contacted GitHub")):
                module.main(["github", "--bundle", str(root), "--repository", "example/mod"])

    def test_github_published_file_is_never_replaced_when_bytes_differ(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = b"new build"
            filename = "ae2webintegration-2.0.0-forge-1.7.10.jar"
            (root / filename).write_bytes(data)
            (root / "notes.md").write_text("Release notes.\n", encoding="utf-8")
            (root / "manifest.json").write_text(json.dumps({
                "artifact": filename, "notes_file": "notes.md",
                "sha512": hashlib.sha512(data).hexdigest(), "sha1": hashlib.sha1(data).hexdigest(),
                "tag": "2.0.0-forge-1.7.10", "release_type": "release",
            }), encoding="utf-8")
            (root / "metadata.json").write_text(json.dumps({
                "tag": "2.0.0-forge-1.7.10", "prerelease": False,
            }), encoding="utf-8")
            def github_process(command, **kwargs):
                if "api" in command:
                    return subprocess.CompletedProcess(command, 0, json.dumps({
                        "body": "Release notes.\n", "prerelease": False, "draft": False,
                        "assets": [{"name": filename}],
                    }), "")
                if "download" in command:
                    download_dir = Path(command[command.index("--dir") + 1])
                    (download_dir / filename).write_bytes(b"old published build")
                    return subprocess.CompletedProcess(command, 0, "", "")
                raise AssertionError("Attempted to mutate an existing published release")
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            with patch("subprocess.run", side_effect=github_process):
                with self.assertRaisesRegex(ValueError, "differs"):
                    module.main(["github", "--bundle", str(root), "--repository", "example/mod", "--publish"])


if __name__ == "__main__":
    unittest.main()
