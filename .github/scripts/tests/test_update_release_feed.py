import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "update_release_feed.py"
TARGETS = ("1.7.10", "1.12.2", "1.20.1", "1.21.1")
REPO_URL = "https://github.com/kuba6000/AE2-Web-Integration"


def release(tag, **overrides):
    result = {
        "tag_name": tag,
        "draft": False,
        "prerelease": False,
        "published_at": "2026-09-07T12:00:00Z",
        "html_url": f"{REPO_URL}/releases/tag/{tag}",
        "assets": [{
            "name": f"ae2webintegration-{tag}{suffix}.jar",
            "state": "uploaded",
            "size": 1024,
            "browser_download_url": f"{REPO_URL}/releases/download/{tag}/ae2webintegration-{tag}{suffix}.jar",
        } for suffix in ("-dev", "-sources", "-slim", "")],
    }
    result.update(overrides)
    return result


class UpdateReleaseFeedTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name) / "version"
        self.repository.mkdir()
        self.git("init")
        for target in TARGETS:
            (self.repository / f"{target}.json").write_text(json.dumps({
                "version": target, "releases": {"stable": None, "prerelease": None},
            }) + "\n", encoding="utf-8")
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-m", "Initialize feed")

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repository), *args],
                              capture_output=True, text=True, check=True).stdout.strip()

    def update(self, releases):
        source = Path(self.temporary.name) / "releases.json"
        source.write_text(json.dumps(releases), encoding="utf-8")
        return subprocess.run([sys.executable, str(SCRIPT), "--repository", str(self.repository),
                               "--releases", str(source), "--author-name", "Release Bot",
                               "--author-email", "bot@example.invalid"], capture_output=True, text=True)

    def feed(self, target="1.7.10"):
        return json.loads((self.repository / f"{target}.json").read_text(encoding="utf-8"))

    def test_publishes_stable_and_pre_with_production_asset_and_unix_seconds(self):
        result = self.update([release("1.0.2-forge-1.7.10"), release("1.1.0-forge-pre-1.7.10")])
        self.assertEqual(0, result.returncode, result.stderr)
        channels = self.feed()["releases"]
        self.assertEqual("1.0.2", channels["stable"]["newest"])
        self.assertEqual("1.1.0", channels["prerelease"]["newest"])
        self.assertEqual(1788782400, channels["prerelease"]["timestamp"])
        self.assertTrue(channels["prerelease"]["github_release_download_url"].endswith("1.1.0-forge-pre-1.7.10.jar"))
        self.assertEqual("1.7.10.json", self.git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"))
        self.assertEqual("Release Bot|bot@example.invalid", self.git("log", "-1", "--format=%an|%ae"))
        self.assertEqual("", self.git("status", "--porcelain"))

    def test_delayed_older_release_cannot_replace_newer_recommendation(self):
        self.assertEqual(0, self.update([release("1.10.0-forge-1.7.10")]).returncode)
        before = self.git("rev-parse", "HEAD")
        result = self.update([release("1.9.0-forge-1.7.10", published_at="2026-09-08T12:00:00Z")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, self.git("rev-parse", "HEAD"))

    def test_reconcile_includes_all_targets_and_preserves_other_channel(self):
        self.assertEqual(0, self.update([release("1.2.0-forge-pre-1.7.10")]).returncode)
        pre = self.feed()["releases"]["prerelease"]
        result = self.update([release("1.1.0-forge-1.7.10"), release("1.1.0-forge-1.12.2"),
                              release("1.1.0-forge-1.20.1"), release("1.1.0-neoforge-1.21.1")])
        self.assertEqual(0, result.returncode, result.stderr)
        for target in TARGETS:
            self.assertEqual("1.1.0", self.feed(target)["releases"]["stable"]["newest"])
        self.assertEqual(pre, self.feed()["releases"]["prerelease"])

    def test_rerun_is_idempotent_and_ignores_drafts_missing_assets_and_snapshots(self):
        releases = [release("1.0.0-forge-1.7.10"),
                    release("2.0.0-forge-1.7.10", draft=True),
                    release("3.0.0-forge-1.7.10", assets=[]),
                    release("4.0.0-snapshot-forge-1.7.10")]
        self.assertEqual(0, self.update(releases).returncode)
        before = self.git("rev-parse", "HEAD")
        result = self.update(releases)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, self.git("rev-parse", "HEAD"))
        self.assertEqual("1.0.0", self.feed()["releases"]["stable"]["newest"])

    def test_ordered_prereleases_use_numeric_identifiers_not_publication_time(self):
        self.assertEqual(0, self.update([release("1.2.0-beta.2-forge-1.7.10")]).returncode)
        result = self.update([release("1.2.0-beta.10-forge-1.7.10", published_at="2026-09-06T12:00:00Z")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("1.2.0-beta.10", self.feed()["releases"]["prerelease"]["newest"])

    def test_old_alpha_is_not_introduced_as_active_recommendation(self):
        result = self.update([release("1.0.0-forge-1.7.10"), release("0.2.1-alpha-forge-1.7.10")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIsNone(self.feed()["releases"]["prerelease"])

    def test_unordered_same_base_pre_does_not_replace_existing_record(self):
        self.assertEqual(0, self.update([release("1.2.0-forge-pre-1.7.10")]).returncode)
        before = self.git("rev-parse", "HEAD")
        result = self.update([release("1.2.0-GTNH-Native-Fluids-Support-forge-pre-1.7.10")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, self.git("rev-parse", "HEAD"))

    def test_only_uploaded_production_artifact_is_eligible(self):
        no_production = release("1.0.0-forge-1.7.10")
        no_production["assets"].pop()
        uploading = release("2.0.0-forge-1.7.10")
        uploading["assets"][-1]["state"] = "starter"
        result = self.update([no_production, uploading])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIsNone(self.feed()["releases"]["stable"])

    def test_dirty_feed_is_not_committed_with_release_update(self):
        (self.repository / "1.12.2.json").write_text("uncommitted", encoding="utf-8")
        before = self.git("rev-parse", "HEAD")
        result = self.update([release("1.0.0-forge-1.7.10")])
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.git("rev-parse", "HEAD"))
        self.assertIsNone(self.feed()["releases"]["stable"])

    def test_ambiguous_new_prereleases_require_explicit_choice(self):
        self.assertEqual(0, self.update([release("1.1.0-forge-pre-1.7.10")]).returncode)
        before = self.git("rev-parse", "HEAD")
        result = self.update([release("1.2.0-zeta-forge-1.7.10"), release("1.2.0-alpha-forge-1.7.10")])
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.git("rev-parse", "HEAD"))
        self.assertEqual("1.1.0", self.feed()["releases"]["prerelease"]["newest"])

    def test_semver_build_metadata_does_not_change_release_order(self):
        result = self.update([release("1.2.0+build.2-forge-1.7.10")])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("1.2.0+build.2", self.feed()["releases"]["stable"]["newest"])

    def test_invalid_semver_suffix_is_not_published(self):
        for version in ("1.2.0-beta..2", "1.2.0-beta.02", "1.2.0+build..2"):
            with self.subTest(version=version):
                before = self.git("rev-parse", "HEAD")
                result = self.update([release(f"{version}-forge-1.7.10")])
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before, self.git("rev-parse", "HEAD"))


if __name__ == "__main__":
    unittest.main()
