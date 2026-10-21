"""Exercise release preparation through its command-line interface."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "release_notes.py"


class ReleaseNotesCliTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.checkout = self.directory / "checkout"
        self.scripts = self.checkout / ".github" / "scripts"
        self.scripts.mkdir(parents=True)
        self.script = self.scripts / "release_notes.py"
        if SCRIPT.exists():
            shutil.copyfile(SCRIPT, self.script)
        self.foreign = self.directory / "other-working-directory"
        self.foreign.mkdir()
        self.commit_number = 0
        self.git("init", "--quiet")
        self.commit()
        self.write("changelog.md", "## Changes\n\nGenerated changes.\n")

    def git(self, *arguments):
        return subprocess.run(
            ["git", "-C", str(self.checkout), *arguments],
            check=True, capture_output=True, encoding="utf-8",
        ).stdout.strip()

    def commit(self):
        self.commit_number += 1
        self.git("-c", "user.name=Release Test", "-c", "user.email=release-test@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--allow-empty", "--quiet", "-m",
                 f"Fixture {self.commit_number}")

    def write(self, relative, content):
        path = self.checkout / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def run_cli(self, tag="2.0.0-forge-1.7.10", minecraft="1.7.10", loader="forge", *extra,
                online=False, environment=None):
        arguments = [sys.executable, str(self.script), "--tag", tag, "--minecraft", minecraft,
                     "--loader", loader, "--repository", "owner/repository",
                     "--output", "build/release/notes.md", *extra]
        if not online:
            arguments.extend(("--changelog-file", "changelog.md"))
        return subprocess.run(
            arguments, cwd=self.foreign, capture_output=True, encoding="utf-8", env=environment,
        )

    def metadata(self):
        return json.loads((self.checkout / "build/release/metadata.json").read_text(encoding="utf-8"))

    def fake_gh(self, program):
        fake = self.write("fake-tools/gh.py", program)
        if os.name == "nt":
            self.write("fake-tools/gh.cmd", f'@"{sys.executable}" "{fake}" %*\n')
        else:
            launcher = self.write("fake-tools/gh", f'#!/bin/sh\nexec "{sys.executable}" "{fake}" "$@"\n')
            launcher.chmod(0o755)
        return dict(os.environ, PATH=str(fake.parent) + os.pathsep + os.environ["PATH"])

    def test_composes_version_introductions_then_changes_then_common_footer(self):
        self.write(".releases/common.md", "<sub>Common notice.</sub>\n")
        self.write(".releases/2.0.0.md", "Version introduction.\n")
        self.write(".releases/2.0.0-forge-1.7.10.md", "Platform addition.\n")

        result = self.run_cli()

        self.assertEqual(result.returncode, 0, result.stderr)
        notes = self.checkout / "build/release/notes.md"
        self.assertEqual(notes.read_text(encoding="utf-8"),
                         "Version introduction.\n\nPlatform addition.\n\n"
                         "## Changes\n\nGenerated changes.\n\n<sub>Common notice.</sub>\n")
        self.assertEqual(self.metadata(), {
            "tag": "2.0.0-forge-1.7.10", "version": "2.0.0", "minecraft": "1.7.10",
            "loader": "forge", "release_type": "release", "snapshot": False,
            "prerelease": False, "previous_tag": None, "notes_file": str(notes),
        })
        self.assertFalse((self.foreign / "build").exists())

    def test_classifies_supported_targets_and_release_channels(self):
        cases = [
            ("2.0.0-forge-1.7.10", "1.7.10", "forge", "2.0.0", "release", False, False),
            ("2.0.0-forge-1.12.2", "1.12.2", "forge", "2.0.0", "release", False, False),
            ("2.0.0-forge-1.20.1", "1.20.1", "forge", "2.0.0", "release", False, False),
            ("2.0.0-neoforge-1.21.1", "1.21.1", "neoforge", "2.0.0", "release", False, False),
            ("2.0.0-alpha.2-forge-1.7.10", "1.7.10", "forge", "2.0.0-alpha.2", "alpha", False, True),
            ("2.0.0-beta.1-forge-1.12.2", "1.12.2", "forge", "2.0.0-beta.1", "beta", False, True),
            ("2.0.0-rc.1-forge-1.20.1", "1.20.1", "forge", "2.0.0-rc.1", "beta", False, True),
            ("2.0.0-preview-neoforge-1.21.1", "1.21.1", "neoforge", "2.0.0-preview", "beta", False, True),
            ("2.0.0-forge-pre-1.7.10", "1.7.10", "forge", "2.0.0", "beta", False, True),
            ("2.0.0-neoforge-1.21.1-pre", "1.21.1", "neoforge", "2.0.0", "beta", False, True),
            ("2.0.0-SNAPSHOT-forge-1.20.1", "1.20.1", "forge", "2.0.0-SNAPSHOT", "beta", True, True),
            ("2.0.0+build.7-forge-1.7.10", "1.7.10", "forge", "2.0.0+build.7", "release", False, False),
        ]
        for tag, minecraft, loader, version, release_type, snapshot, prerelease in cases:
            with self.subTest(tag=tag):
                result = self.run_cli(tag, minecraft, loader)
                self.assertEqual(result.returncode, 0, result.stderr)
                metadata = self.metadata()
                self.assertEqual((metadata["version"], metadata["release_type"], metadata["snapshot"],
                                  metadata["prerelease"]), (version, release_type, snapshot, prerelease))

    def test_rejects_invalid_tags_and_mismatched_targets_before_writing_outputs(self):
        cases = [
            ("2.0.0-forge-1.7.10", "1.20.1", "forge"),
            ("2.0.0-forge-1.7.10", "1.7.10", "neoforge"),
            ("2.0.0-forge-1.21.1", "1.21.1", "forge"),
            ("2.0.0-neoforge-1.20.1", "1.20.1", "neoforge"),
            ("2.0.0-forge-1.7.10", "1.99.1", "forge"),
            ("v2.0.0-forge-1.7.10", "1.7.10", "forge"),
            ("2.0-forge-1.7.10", "1.7.10", "forge"),
            ("02.0.0-forge-1.7.10", "1.7.10", "forge"),
            ("2.0.0-beta.01-forge-1.7.10", "1.7.10", "forge"),
            ("2.0.0-beta..1-forge-1.7.10", "1.7.10", "forge"),
            ("2.0.0+build..1-forge-1.7.10", "1.7.10", "forge"),
            ("../outside-forge-1.7.10", "1.7.10", "forge"),
            ("..\\outside-forge-1.7.10", "1.7.10", "forge"),
            ("2.0.0-forge-1.7.10\nprerelease=false", "1.7.10", "forge"),
        ]
        for tag, minecraft, loader in cases:
            with self.subTest(tag=tag, minecraft=minecraft, loader=loader):
                result = self.run_cli(tag, minecraft, loader)
                self.assertNotEqual(result.returncode, 0)
                self.assertTrue(result.stderr.strip())
                self.assertFalse((self.checkout / "build").exists())

    def test_preserves_utf8_and_markdown_indentation_with_optional_notes_absent(self):
        (self.checkout / "changelog.md").write_bytes("    Zażółć gęślą jaźń.\r\n\r\nUTF-8: 日本語.\r\n".encode("utf-8"))
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.checkout / "build/release/notes.md").read_bytes(),
                         "    Zażółć gęślą jaźń.\n\nUTF-8: 日本語.\n".encode("utf-8"))

    def test_chooses_previous_non_snapshot_tag_from_the_same_release_line(self):
        for minecraft, loader in (("1.7.10", "forge"), ("1.12.2", "forge"),
                                  ("1.20.1", "forge"), ("1.21.1", "neoforge")):
            with self.subTest(minecraft=minecraft):
                self.commit()
                previous = f"1.9.0-{loader}-{minecraft}"
                self.git("-c", "user.name=Release Test", "-c", "user.email=release-test@example.invalid",
                         "tag", "-a", previous, "-m", "Previous release")
                self.commit()
                self.git("tag", f"2.0.0-SNAPSHOT-{loader}-{minecraft}")
                self.git("tag", f"invalid-{loader}-{minecraft}")
                other = "9.0.0-neoforge-1.21.1" if loader == "forge" else "9.0.0-forge-1.20.1"
                if other not in self.git("tag", "--list").splitlines():
                    self.git("tag", other)
                self.commit()
                current = f"2.0.0-{loader}-{minecraft}"
                self.git("tag", current)
                result = self.run_cli(current, minecraft, loader)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.metadata()["previous_tag"], previous)

    def test_appends_single_line_github_outputs_for_workflow_consumers(self):
        self.git("tag", "1.9.0-forge-1.7.10")
        output = self.write("github-output.txt", "existing=value\n")
        result = self.run_cli("2.0.0-rc.1-forge-1.7.10", "1.7.10", "forge",
                              "--github-output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        values = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
        self.assertEqual(values, {"existing": "value", "release_type": "beta", "snapshot": "false",
                                  "prerelease": "true", "version": "2.0.0-rc.1",
                                  "previous_tag": "1.9.0-forge-1.7.10"})

    def test_generates_github_notes_with_the_selected_previous_tag(self):
        self.git("tag", "1.9.0-forge-1.7.10")
        self.commit()
        environment = self.fake_gh("""import json
import sys

arguments = sys.argv[1:]
if 'repos/owner/repository/releases/generate-notes' not in arguments:
    sys.exit(8)
if '--input' in arguments:
    payload = json.load(sys.stdin)
else:
    payload = dict(arguments[index + 1].split('=', 1) for index, value in enumerate(arguments)
                   if value in ('-f', '--raw-field'))
if payload.get('previous_tag_name') != '1.9.0-forge-1.7.10' or not payload.get('target_commitish'):
    sys.exit(9)
print(json.dumps({'body': 'Generated notes for ' + payload['tag_name'] + ' since ' + payload['previous_tag_name']}))
""")

        result = self.run_cli(online=True, environment=environment)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.checkout / "build/release/notes.md").read_text(encoding="utf-8"),
                         "Generated notes for 2.0.0-forge-1.7.10 since 1.9.0-forge-1.7.10\n")

    def test_rejects_repository_path_injection_before_writing_outputs(self):
        for repository in ("owner/repository/extra", "../repository", "owner/..", "owner/repo\nother=value"):
            with self.subTest(repository=repository):
                result = self.run_cli("2.0.0-forge-1.7.10", "1.7.10", "forge",
                                      "--repository", repository)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.checkout / "build").exists())

    def test_previous_tag_uses_current_tag_ancestry_and_the_first_parent_line(self):
        branch = self.git("branch", "--show-current")
        self.git("tag", "1.0.0-forge-1.7.10")
        self.git("checkout", "--quiet", "-b", "side")
        self.commit()
        self.git("tag", "99.0.0-forge-1.7.10")
        self.git("checkout", "--quiet", branch)
        self.commit()
        self.git("tag", "1.9.0-forge-1.7.10")
        self.git("-c", "user.name=Release Test", "-c", "user.email=release-test@example.invalid",
                 "-c", "commit.gpgsign=false", "merge", "--quiet", "--no-ff", "side", "-m", "Merge fixture")
        self.git("tag", "2.0.0-forge-1.7.10")
        self.commit()
        self.git("tag", "3.0.0-forge-1.7.10")

        result = self.run_cli()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.metadata()["previous_tag"], "1.9.0-forge-1.7.10")

    def test_prerelease_authored_intro_and_common_crlf_are_optional_and_preserved(self):
        (self.write(".releases/common.md", "")).write_bytes("Common: zażółć.\r\n".encode("utf-8"))
        self.write(".releases/2.0.0-beta.1.md", "Beta introduction.\n")
        self.write(".releases/2.0.0.md", "Stable introduction must not be selected.\n")

        result = self.run_cli("2.0.0-beta.1-forge-1.7.10")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.checkout / "build/release/notes.md").read_bytes(),
                         "Beta introduction.\n\n## Changes\n\nGenerated changes.\n\nCommon: zażółć.\n".encode("utf-8"))

    def test_first_release_uses_local_ancestry_instead_of_github_global_previous_tag(self):
        self.git("tag", "9.0.0-neoforge-1.21.1")
        self.commit()
        self.git("tag", "2.0.0-forge-1.7.10")
        self.commit()
        environment = self.fake_gh("import sys\nsys.exit(9)\n")

        result = self.run_cli(online=True, environment=environment)

        self.assertEqual(result.returncode, 0, result.stderr)
        notes = (self.checkout / "build/release/notes.md").read_text(encoding="utf-8")
        self.assertIn("Fixture 2", notes)
        self.assertIn("Fixture 1", notes)
        self.assertNotIn("Fixture 3", notes)
        self.assertIsNone(self.metadata()["previous_tag"])


if __name__ == "__main__":
    unittest.main()
