#!/usr/bin/env python3
"""Freeze monorepo release variants and create their tags at one verified commit."""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen


TARGETS = {"1.7.10": "forge", "1.12.2": "forge", "1.20.1": "forge", "1.21.1": "neoforge", "26.1.2": "neoforge"}
SHA = re.compile(r"[0-9a-f]{40}")
SEMVER = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
                    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
                    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")


class ReleaseError(RuntimeError):
    pass


def git(checkout, *arguments):
    result = subprocess.run(["git", "-C", str(checkout), *arguments],
                            capture_output=True, text=True)
    if result.returncode:
        raise ReleaseError(result.stderr.strip() or result.stdout.strip() or "Git command failed")
    return result.stdout.strip()


def github_api(path, token=None):
    url = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    token = token or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    with urlopen(Request(f"{url}/{path}", headers=headers), timeout=30) as response:
        return json.load(response)


def version_from_tag(tag):
    match = SEMVER.fullmatch(tag)
    if not match or (match[1] and any(re.fullmatch(r"0[0-9]+", part) for part in match[1].split("."))):
        raise ReleaseError("Source tag must be a valid bare SemVer")
    if any(tag.endswith(f"-{loader}-{branch}") for branch, loader in TARGETS.items()):
        raise ReleaseError("Source tag must be a base version without a Minecraft/loader suffix")
    return tag


def source_commit(args):
    git(args.checkout, "fetch", "--no-tags", "origin",
        f"+refs/tags/{args.source_tag}:refs/release-coordinator/source",
        f"+refs/heads/{args.branch}:refs/remotes/origin/{args.branch}")
    source_object = git(args.checkout, "rev-parse", "refs/release-coordinator/source")
    sha = git(args.checkout, "rev-parse", "refs/release-coordinator/source^{commit}")
    if sha != args.core_sha:
        raise ReleaseError("Source tag changed from the requested commit")
    git(args.checkout, "merge-base", "--is-ancestor", sha, f"refs/remotes/origin/{args.branch}")
    return source_object


def require_push_ci(args, branch, sha):
    runs = []
    page = 1
    while True:
        query = urlencode({"branch": branch, "event": "push", "head_sha": sha, "per_page": 100, "page": page})
        response = github_api(f"repos/{args.repository}/actions/workflows/build-and-test.yml/runs?{query}")
        if response["total_count"] > 1000:
            raise ReleaseError(f"{branch} has too many matching CI runs to establish latest readiness")
        runs.extend(response["workflow_runs"])
        if len(runs) >= response["total_count"]:
            break
        if not response["workflow_runs"]:
            raise ReleaseError(f"{branch} CI results changed during pagination; retry preparation")
        page += 1
    if not runs:
        raise ReleaseError(f"{branch} {sha} has no exact-commit push CI; finish pin updates/CI and rerun preparation")
    # Rerunning an older run does not give it a newer ID, but does update its timestamp.
    latest = max(runs, key=lambda run: (run["updated_at"], run["id"], run["run_attempt"]))
    if (latest["head_sha"] != sha or latest["head_branch"] != branch or latest["event"] != "push"
            or latest["path"] != ".github/workflows/build-and-test.yml"
            or latest["status"] != "completed" or latest["conclusion"] != "success"):
        raise ReleaseError(f"{branch} {sha} latest push CI is not successful; finish CI and rerun preparation")


def release_targets(tag, sha):
    for minecraft, loader in TARGETS.items():
        suffix = f"-{loader}-{minecraft}"
        if tag.endswith(suffix):
            version_from_tag(tag[:-len(suffix)])
            return [{"minecraft": minecraft, "loader": loader, "directory": f"{loader}-{minecraft}",
                     "sha": sha, "tag": tag}]
    if re.search(r"-(?:neo)?forge-", tag):
        raise ReleaseError("Unsupported release variant")
    version = version_from_tag(tag)
    return [{"minecraft": minecraft, "loader": loader, "directory": f"{loader}-{minecraft}",
             "sha": sha, "tag": f"{version}-{loader}-{minecraft}"}
            for minecraft, loader in TARGETS.items()]


def tag_exists(args, target):
    ref = f"refs/tags/{target['tag']}"
    refs = dict(line.split("\t")[::-1] for line in
                git(args.checkout, "ls-remote", "--tags", "origin", ref, f"{ref}^{{}}").splitlines())
    if ref not in refs:
        return False
    if refs.get(f"{ref}^{{}}", refs[ref]) != target["sha"]:
        raise ReleaseError(f"Conflicting destination tag {target['tag']}; tags are never overwritten")
    return True


def prepare(args):
    source_object = source_commit(args)
    targets = release_targets(args.source_tag, args.core_sha)
    for target in targets:
        if git(args.checkout, "cat-file", "-t", f"{args.core_sha}:{target['directory']}/build.gradle") != "blob":
            raise ReleaseError("Missing adapter build")
        tag_exists(args, target)
    git(args.checkout, "fetch", "--no-tags", "origin", "refs/heads/version")
    notes_sha = git(args.checkout, "rev-parse", "FETCH_HEAD^{commit}")
    plan = {"schema": 2, "repository": args.repository, "source_tag": args.source_tag,
            "source_object": source_object, "core_sha": args.core_sha, "branch": args.branch,
            "notes_sha": notes_sha, "targets": targets}
    args.plan.write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(plan))


def publish(args):
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    expected = release_targets(args.source_tag, args.core_sha)
    if (plan.get("schema") != 2 or plan.get("repository") != args.repository
            or plan.get("source_tag") != args.source_tag or plan.get("core_sha") != args.core_sha
            or plan.get("branch") != args.branch or plan.get("targets") != expected
            or not SHA.fullmatch(plan.get("notes_sha", ""))):
        raise ReleaseError("Release plan does not match this request")
    if source_commit(args) != plan["source_object"]:
        raise ReleaseError("Source tag changed after preparation")
    require_push_ci(args, args.branch, args.core_sha)
    existing = [tag_exists(args, target) for target in expected]
    token = os.environ.get("GH_TOKEN", "").strip()
    if not token:
        raise ReleaseError("GH_TOKEN is required to create release tags")
    authentication = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::add-mask::{authentication}", flush=True)
    for target, exists in zip(expected, existing):
        if not exists:
            git(args.checkout, "-c", f"http.https://github.com/.extraheader=AUTHORIZATION: basic {authentication}",
                "push", "--no-follow-tags", "origin", f"{args.core_sha}:refs/tags/{target['tag']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "publish"))
    parser.add_argument("--checkout", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-tag", required=True)
    parser.add_argument("--core-sha", required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--branch", choices=("core", "master", "main"), required=True)
    args = parser.parse_args()
    try:
        if not SHA.fullmatch(args.core_sha):
            raise ReleaseError("Core commit must be a full lowercase 40-character SHA-1")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
            raise ReleaseError("Repository must be owner/name")
        if args.command == "prepare":
            prepare(args)
        else:
            publish(args)
    except (ReleaseError, OSError, ValueError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
