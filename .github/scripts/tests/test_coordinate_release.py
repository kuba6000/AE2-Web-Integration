import importlib.util
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("coordinator", Path(__file__).resolve().parents[1] / "coordinate_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class CoordinatorTest(unittest.TestCase):
    def test_bare_release_has_four_variants_at_one_commit(self):
        targets = release.release_targets("2.1.0", "a" * 40)
        self.assertEqual(len(targets), 4)
        self.assertEqual({t["sha"] for t in targets}, {"a" * 40})
        self.assertEqual({t["tag"] for t in targets}, {
            "2.1.0-forge-1.7.10", "2.1.0-forge-1.12.2", "2.1.0-forge-1.20.1", "2.1.0-neoforge-1.21.1"})

    def test_single_variant_does_not_release_other_platforms(self):
        target, = release.release_targets("2.1.0-beta.2-neoforge-1.21.1", "b" * 40)
        self.assertEqual(target["directory"], "neoforge-1.21.1")
        self.assertEqual(target["tag"], "2.1.0-beta.2-neoforge-1.21.1")

    def test_invalid_tags_are_rejected(self):
        for tag in ("nope", "2.1.0-beta.01", "2.1.0-forge-1.99.1", "2.1.0-forge-1.21.1"):
            with self.subTest(tag=tag), self.assertRaises(release.ReleaseError):
                release.release_targets(tag, "a" * 40)

    def test_existing_tag_must_resolve_to_same_commit(self):
        args = SimpleNamespace(checkout=Path("."))
        target = {"tag": "2.1.0-forge-1.7.10", "sha": "a" * 40}
        with patch.object(release, "git", return_value="b" * 40 + "\trefs/tags/" + target["tag"]):
            with self.assertRaises(release.ReleaseError): release.tag_exists(args, target)
        with patch.object(release, "git", return_value="a" * 40 + "\trefs/tags/" + target["tag"]):
            self.assertTrue(release.tag_exists(args, target))

    def test_inflight_ci_is_not_release_ready(self):
        args = SimpleNamespace(repository="fixture/repo")
        run = {"updated_at":"now", "id":1,"run_attempt":1,"head_sha":"a"*40,
               "head_branch":"master","event":"push","path":".github/workflows/build-and-test.yml",
               "status":"in_progress","conclusion":None}
        with patch.object(release,"github_api",return_value={"total_count":1,"workflow_runs":[run]}):
            with self.assertRaises(release.ReleaseError): release.require_push_ci(args,"master","a"*40)
            run.update(status="completed",conclusion="success")
            release.require_push_ci(args,"master","a"*40)
