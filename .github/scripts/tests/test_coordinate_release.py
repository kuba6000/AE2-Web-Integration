import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


SCRIPT = Path(__file__).resolve().parents[1] / "coordinate_release.py"
TARGETS = ("1.7.10", "1.12.2", "1.20.1", "1.21.1")
TAGS = ("2.0.0-beta.1-forge-1.7.10", "2.0.0-beta.1-forge-1.12.2",
        "2.0.0-beta.1-forge-1.20.1", "2.0.0-beta.1-neoforge-1.21.1")


class CoordinateReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.remote = self.root / "remote.git"
        self.checkout = self.root / "checkout"
        self.plan = self.root / "plan.json"
        self.source.mkdir()
        self.git(self.source, "init", "-b", "core")
        (self.source / "README.md").write_text("core\n", encoding="utf-8")
        self.commit("Initial core")
        self.core_sha = self.git(self.source, "rev-parse", "HEAD")
        self.git(self.source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "tag.gpgsign=false", "tag", "-a", "2.0.0-beta.1", "-m", "Core release")
        self.heads = {}
        for branch in TARGETS:
            self.git(self.source, "checkout", "-b", branch, "core")
            workflow = self.source / ".github/workflows/release-tags.yml"
            workflow.parent.mkdir(parents=True, exist_ok=True)
            workflow.write_text("name: Release tagged build\n", encoding="utf-8")
            self.git(self.source, "add", ".github")
            self.git(self.source, "update-index", "--add", "--cacheinfo", f"160000,{self.core_sha},core")
            self.commit(f"Prepare {branch}")
            self.heads[branch] = self.git(self.source, "rev-parse", "HEAD")
        self.git(self.root, "init", "--bare", str(self.remote))
        self.git(self.source, "remote", "add", "origin", str(self.remote))
        self.git(self.source, "push", "origin", "--all")
        self.git(self.source, "push", "origin", "refs/tags/2.0.0-beta.1")
        self.git(self.root, "clone", "--branch", "core", str(self.remote), str(self.checkout))
        self.runs = {}
        for index, (branch, sha) in enumerate({"core": self.core_sha, **self.heads}.items()):
            self.runs[branch] = [self.run_record(branch, sha, index + 1)]
        self.login = "kuba6000-agent"
        self.bot_token = "bot-fixture"
        self.api_writes = []

        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                request = urlparse(self.path)
                if request.path == "/user":
                    value = {"login": fixture.login if self.headers.get("Authorization") == "Bearer bot-fixture"
                             else "other-account"}
                elif request.path == "/repos/fixture/releases/actions/workflows/build-and-test.yml/runs":
                    query = parse_qs(request.query)
                    runs = [run for run in fixture.runs.get(query.get("branch", [""])[0], [])
                            if all(run[field] == query.get(parameter, [""])[0]
                                   for field, parameter in (("head_sha", "head_sha"), ("event", "event")))]
                    size = int(query.get("per_page", ["30"])[0])
                    start = (int(query.get("page", ["1"])[0]) - 1) * size
                    value = {"total_count": len(runs), "workflow_runs": runs[start:start + size]}
                else:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(value).encode("utf-8"))

            def do_POST(self):
                fixture.api_writes.append(self.path)
                self.send_error(405)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def git(self, checkout, *args):
        return subprocess.run(["git", "-C", str(checkout), *args], capture_output=True,
                              text=True, check=True).stdout.strip()

    def commit(self, message):
        self.git(self.source, "add", "README.md")
        self.git(self.source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-m", message)

    @staticmethod
    def run_record(branch, sha, identifier, **overrides):
        run = {"id": identifier, "run_attempt": 1, "head_sha": sha, "head_branch": branch,
               "event": "push", "status": "completed", "conclusion": "success",
               "path": ".github/workflows/build-and-test.yml", "updated_at": "2026-10-21T08:00:00Z"}
        run.update(overrides)
        return run

    def coordinate(self, command, **overrides):
        arguments = {"checkout": str(self.checkout), "repository": "fixture/releases",
                     "source-tag": "2.0.0-beta.1", "core-sha": self.core_sha,
                     "plan": str(self.plan)}
        arguments.update(overrides)
        environment = dict(os.environ, GITHUB_API_URL=f"http://127.0.0.1:{self.server.server_port}",
                           GH_TOKEN="read-fixture", CORE_PIN_BOT_TOKEN=self.bot_token)
        if self.bot_token is None:
            environment.pop("CORE_PIN_BOT_TOKEN")
        return subprocess.run([sys.executable, str(SCRIPT), command,
                               *(value for key, item in arguments.items() for value in (f"--{key}", item))],
                              capture_output=True, text=True, env=environment)

    def native_refs(self):
        return self.git(self.checkout, "ls-remote", "--tags", "origin", "*-forge-*", "*-neoforge-*")

    def advance(self, branch, pin=None):
        self.git(self.source, "checkout", branch)
        if pin is not None:
            self.git(self.source, "update-index", "--cacheinfo", f"160000,{pin},core")
        (self.source / "README.md").write_text(f"advanced {branch}\n", encoding="utf-8")
        self.commit(f"Advance {branch}")
        self.git(self.source, "push", "origin", f"refs/heads/{branch}")
        return self.git(self.source, "rev-parse", "HEAD")

    def remote_tag(self, tag, sha, annotated=False):
        arguments = ["-a", "-m", "Existing release"] if annotated else []
        self.git(self.source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "-c", "tag.gpgsign=false", "tag", *arguments, tag, sha)
        self.git(self.source, "push", "origin", f"refs/tags/{tag}")

    def test_prepares_exact_four_commits_from_annotated_core_tag_without_publishing(self):
        result = self.coordinate("prepare")

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(self.plan.read_text(encoding="utf-8"))
        self.assertEqual(self.core_sha, plan["core_sha"])
        self.assertEqual("2.0.0-beta.1", plan["source_tag"])
        self.assertEqual(list(zip(TARGETS, TAGS, self.heads.values())),
                         [(item["branch"], item["tag"], item["sha"]) for item in plan["targets"]])
        self.assertEqual("", self.native_refs())

    def test_missing_bot_token_skips_publication_without_requiring_a_plan(self):
        before = self.git(self.checkout, "ls-remote", "origin")
        for token in (None, "", " \t\n"):
            with self.subTest(token=token):
                self.bot_token = token
                result = self.coordinate("publish")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("CORE_PIN_BOT_TOKEN", result.stdout)
                self.assertFalse(self.plan.exists())
                self.assertEqual(before, self.git(self.checkout, "ls-remote", "origin"))
                self.assertEqual([], self.api_writes)

    def test_rewritten_native_history_reports_the_frozen_commit_without_publishing(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        branch = "1.21.1"
        self.git(self.source, "push", "--force", "origin", f"{self.core_sha}:refs/heads/{branch}")

        result = self.coordinate("publish")

        self.assertNotEqual(0, result.returncode)
        self.assertIn(branch, result.stderr)
        self.assertIn(self.heads[branch], result.stderr)
        self.assertEqual("", self.native_refs())

    def test_frozen_release_survives_newer_core_and_native_pins(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        frozen = self.plan.read_bytes()
        newer_core = self.advance("core")
        for branch in TARGETS:
            self.advance(branch, pin=newer_core)

        result = self.coordinate("publish")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(sorted(f"{sha}\trefs/tags/{tag}" for sha, tag in zip(self.heads.values(), TAGS)),
                         sorted(self.native_refs().splitlines()))
        self.assertEqual(frozen, self.plan.read_bytes())

    def test_publishes_exact_four_planned_refs_and_can_repeat(self):
        prepared = self.coordinate("prepare")
        self.assertEqual(0, prepared.returncode, prepared.stderr)
        frozen = self.plan.read_bytes()
        hook = self.remote / "hooks/pre-receive"
        hook.write_text("#!/bin/sh\ncount=0\nwhile read old new ref; do\n"
                        "count=$((count + 1))\ndone\n[ \"$count\" -eq 1 ]\n", encoding="utf-8")
        hook.chmod(0o755)

        published = self.coordinate("publish")

        self.assertEqual(0, published.returncode, published.stderr)
        self.assertEqual(sorted(f"{sha}\trefs/tags/{tag}" for sha, tag in zip(self.heads.values(), TAGS)),
                         sorted(self.native_refs().splitlines()))
        repeated = self.coordinate("publish")
        self.assertEqual(0, repeated.returncode, repeated.stderr)
        self.assertEqual(frozen, self.plan.read_bytes())
        self.assertEqual([], self.api_writes)

    def test_later_failed_or_pending_rerun_cannot_be_hidden_by_a_green_newer_run_id(self):
        branch = "1.12.2"
        for status, conclusion in (("completed", "failure"), ("in_progress", None), ("queued", None)):
            with self.subTest(status=status):
                self.runs[branch] = [
                    self.run_record(branch, self.heads[branch], 100),
                    self.run_record(branch, self.heads[branch], 3, run_attempt=2, status=status,
                                    conclusion=conclusion, updated_at="2026-10-21T09:00:00Z"),
                ]

                result = self.coordinate("prepare")

                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.plan.exists())
                self.assertEqual("", self.native_refs())

    def test_ci_pagination_cannot_hide_a_later_failed_attempt_of_an_old_run(self):
        branch = "1.7.10"
        self.runs[branch] = [self.run_record(branch, self.heads[branch], identifier)
                             for identifier in range(101, 1, -1)]
        self.runs[branch].append(self.run_record(branch, self.heads[branch], 1, run_attempt=2,
                                               conclusion="failure", updated_at="2026-10-21T09:00:00Z"))

        result = self.coordinate("prepare")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.plan.exists())
        self.assertEqual("", self.native_refs())

    def test_partial_transport_failure_keeps_published_refs_and_retries_the_frozen_commits(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        frozen = self.plan.read_bytes()
        hook = self.remote / "hooks/update"
        hook.write_text("#!/bin/sh\ncase \"$1\" in\n"
                        "refs/tags/2.0.0-beta.1-forge-1.20.1) exit 1;;\nesac\n", encoding="utf-8")
        hook.chmod(0o755)

        failed = self.coordinate("publish")

        self.assertNotEqual(0, failed.returncode)
        first_two = sorted(f"{sha}\trefs/tags/{tag}" for sha, tag in zip(list(self.heads.values())[:2], TAGS[:2]))
        self.assertEqual(first_two, sorted(self.native_refs().splitlines()))
        advanced = self.advance("1.20.1")
        self.runs["1.20.1"].append(self.run_record("1.20.1", advanced, 20, conclusion="failure"))
        hook.unlink()

        retried = self.coordinate("publish")

        self.assertEqual(0, retried.returncode, retried.stderr)
        self.assertEqual(sorted(f"{sha}\trefs/tags/{tag}" for sha, tag in zip(self.heads.values(), TAGS)),
                         sorted(self.native_refs().splitlines()))
        self.assertEqual(frozen, self.plan.read_bytes())

    def test_conflict_on_fourth_target_blocks_all_tag_writes_during_preparation(self):
        self.remote_tag(TAGS[-1], self.core_sha)
        before = self.native_refs()

        result = self.coordinate("prepare")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.plan.exists())
        self.assertEqual(before, self.native_refs())

    def test_conflict_introduced_after_preparation_blocks_all_publication_writes(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        self.remote_tag(TAGS[-1], self.core_sha)
        before = self.native_refs()

        result = self.coordinate("publish")

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.native_refs())

    def test_existing_annotated_native_tag_on_same_commit_is_preserved(self):
        self.remote_tag(TAGS[0], self.heads["1.7.10"], annotated=True)
        original = self.git(self.checkout, "ls-remote", "--tags", "origin", f"refs/tags/{TAGS[0]}")
        self.assertEqual(0, self.coordinate("prepare").returncode)

        result = self.coordinate("publish")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(original, self.git(self.checkout, "ls-remote", "--tags", "origin", f"refs/tags/{TAGS[0]}"))
        self.assertEqual(self.heads["1.7.10"],
                         self.git(self.checkout, "ls-remote", "--tags", "origin", f"refs/tags/{TAGS[0]}^{{}}").split("\t")[0])

    def test_mixed_core_pins_on_fourth_target_fail_without_a_plan_or_tags(self):
        advanced = self.advance("1.21.1", pin=self.heads["1.7.10"])
        self.runs["1.21.1"] = [self.run_record("1.21.1", advanced, 30)]

        result = self.coordinate("prepare")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.plan.exists())
        self.assertEqual("", self.native_refs())

    def test_old_green_head_or_pr_ci_does_not_establish_exact_push_readiness(self):
        branch = "1.21.1"
        for run in (self.run_record(branch, self.core_sha, 30),
                    self.run_record(branch, self.heads[branch], 31, event="pull_request")):
            with self.subTest(run=run):
                self.runs[branch] = [run]

                result = self.coordinate("prepare")

                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.plan.exists())
                self.assertEqual("", self.native_refs())

    def test_publication_rechecks_exact_ci_after_plan_was_frozen(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        self.runs["1.21.1"][0].update(run_attempt=2, conclusion="failure", updated_at="2026-10-21T09:00:00Z")

        result = self.coordinate("publish")

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", self.native_refs())

    def test_publication_requires_approved_bot_token_and_identity(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        for token, login in (("other-fixture", "kuba6000-agent"),
                             ("bot-fixture", "other-account")):
            with self.subTest(token=token, login=login):
                self.bot_token = token
                self.login = login

                result = self.coordinate("publish")

                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", self.native_refs())

    def test_invalid_source_tag_or_core_sha_never_prepares_or_publishes(self):
        self.remote_tag("release/2.0.0-beta.1", self.core_sha)
        for tag in ("release/2.0.0-beta.1", "v2.0.0", "2.0", "02.0.0", "2.0.0-beta.01",
                    "2.0.0-beta..1", "2.0.0+build..1", "2.0.\u0660", "2.0.0-\u0661", "2.0.0/extra"):
            with self.subTest(tag=tag):
                result = self.coordinate("prepare", **{"source-tag": tag})
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.plan.exists())
                self.assertEqual("", self.native_refs())
        for sha in (self.core_sha[:12], self.core_sha.upper(), self.heads["1.7.10"]):
            with self.subTest(sha=sha):
                result = self.coordinate("prepare", **{"core-sha": sha})
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.plan.exists())
                self.assertEqual("", self.native_refs())

    def test_full_semver_metadata_is_kept_in_all_four_planned_tags(self):
        self.remote_tag("2.0.0-rc.1+build.007", self.core_sha)

        result = self.coordinate("prepare", **{"source-tag": "2.0.0-rc.1+build.007"})

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(self.plan.read_text(encoding="utf-8"))
        self.assertEqual(["2.0.0-rc.1+build.007-forge-1.7.10", "2.0.0-rc.1+build.007-forge-1.12.2",
                          "2.0.0-rc.1+build.007-forge-1.20.1", "2.0.0-rc.1+build.007-neoforge-1.21.1"],
                         [item["tag"] for item in plan["targets"]])
        self.assertEqual("", self.native_refs())

    def test_bare_pre_source_tag_preserves_the_version_in_all_native_tags(self):
        self.remote_tag("2.0.1-pre", self.core_sha)

        result = self.coordinate("prepare", **{"source-tag": "2.0.1-pre"})

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(self.plan.read_text(encoding="utf-8"))
        self.assertEqual(["2.0.1-pre-forge-1.7.10", "2.0.1-pre-forge-1.12.2",
                          "2.0.1-pre-forge-1.20.1", "2.0.1-pre-neoforge-1.21.1"],
                         [item["tag"] for item in plan["targets"]])
        self.assertEqual("", self.native_refs())

    def test_source_commit_outside_core_history_is_rejected(self):
        tree = self.git(self.source, "rev-parse", "core^{tree}")
        alien = self.git(self.source, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                         "commit-tree", tree, "-m", "Unrelated history")
        self.remote_tag("3.0.0", alien)

        result = self.coordinate("prepare", **{"source-tag": "3.0.0", "core-sha": alien})

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.plan.exists())
        self.assertEqual("", self.native_refs())

    def test_native_branch_without_release_workflow_cannot_be_tagged(self):
        branch = "1.21.1"
        self.git(self.source, "checkout", branch)
        self.git(self.source, "rm", ".github/workflows/release-tags.yml")
        self.commit("Remove native release workflow")
        sha = self.git(self.source, "rev-parse", "HEAD")
        self.git(self.source, "push", "origin", f"refs/heads/{branch}")
        self.runs[branch] = [self.run_record(branch, sha, 30)]

        result = self.coordinate("prepare")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.plan.exists())
        self.assertEqual("", self.native_refs())

    def test_plan_is_not_replaced_when_preparation_is_repeated(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        frozen = self.plan.read_bytes()
        advanced = self.advance("1.7.10")
        self.runs["1.7.10"].append(self.run_record("1.7.10", advanced, 30))

        result = self.coordinate("prepare")

        self.assertNotEqual(0, result.returncode)
        self.assertEqual(frozen, self.plan.read_bytes())
        self.assertEqual("", self.native_refs())

    def test_changed_source_tag_object_cannot_publish_even_at_the_same_core_commit(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        self.git(self.source, "tag", "-d", "2.0.0-beta.1")
        self.git(self.source, "tag", "2.0.0-beta.1", self.core_sha)
        self.git(self.source, "push", "--force", "origin", "refs/tags/2.0.0-beta.1")

        result = self.coordinate("publish")

        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", self.native_refs())

    def test_modified_plan_cannot_change_repository_source_or_destination_tags(self):
        self.assertEqual(0, self.coordinate("prepare").returncode)
        frozen = self.plan.read_text(encoding="utf-8")
        for field, value in (("schema", True), ("repository", "other/repository"),
                             ("source_tag", "3.0.0"), ("core_sha", self.heads["1.7.10"]),
                             ("targets", [])):
            with self.subTest(field=field):
                plan = json.loads(frozen)
                plan[field] = value
                self.plan.write_text(json.dumps(plan), encoding="utf-8")

                result = self.coordinate("publish")

                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", self.native_refs())
        plan = json.loads(frozen)
        plan["targets"][-1]["tag"] = "unexpected-native-tag"
        self.plan.write_text(json.dumps(plan), encoding="utf-8")
        self.assertNotEqual(0, self.coordinate("publish").returncode)
        self.assertEqual("", self.native_refs())

    def test_native_release_name_cannot_be_used_as_a_core_source_version(self):
        for index, tag in enumerate(TAGS):
            with self.subTest(tag=tag):
                self.remote_tag(tag, self.core_sha)
                before = self.native_refs()
                output = self.root / f"native-source-{index}.json"

                result = self.coordinate("prepare", **{"source-tag": tag, "plan": str(output)})

                self.assertNotEqual(0, result.returncode)
                self.assertFalse(output.exists())
                self.assertEqual(before, self.native_refs())


if __name__ == "__main__":
    unittest.main()
