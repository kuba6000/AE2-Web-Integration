#!/usr/bin/env python3
"""Freeze and publish the four native tags requested by a core release tag."""

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


TARGETS = {"1.7.10": "forge", "1.12.2": "forge", "1.20.1": "forge", "1.21.1": "neoforge"}
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
        "+refs/heads/core:refs/remotes/origin/core",
        *(f"+refs/heads/{branch}:refs/remotes/origin/{branch}" for branch in TARGETS))
    source_object = git(args.checkout, "rev-parse", "refs/release-coordinator/source")
    core_sha = git(args.checkout, "rev-parse", "refs/release-coordinator/source^{commit}")
    if core_sha != args.core_sha:
        raise ReleaseError("Source tag does not resolve to the requested core commit")
    try:
        git(args.checkout, "merge-base", "--is-ancestor", core_sha, "refs/remotes/origin/core")
    except ReleaseError as error:
        raise ReleaseError("Source commit must belong to core history") from error
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


def require_native_commit(args, branch, sha):
    entry = git(args.checkout, "ls-tree", sha, "--", "core")
    if entry != f"160000 commit {args.core_sha}\tcore":
        raise ReleaseError(f"{branch} {sha} is not pinned to tagged core {args.core_sha}; finish pin updates and rerun preparation")
    if git(args.checkout, "cat-file", "-t", f"{sha}:.github/workflows/release-tags.yml") != "blob":
        raise ReleaseError(f"{branch} has no native release workflow")
    require_push_ci(args, branch, sha)


def tag_exists(args, target):
    ref = f"refs/tags/{target['tag']}"
    refs = dict(line.split("\t")[::-1] for line in
                git(args.checkout, "ls-remote", "--tags", "origin", ref, f"{ref}^{{}}").splitlines())
    if ref not in refs:
        return False
    if refs.get(f"{ref}^{{}}", refs[ref]) != target["sha"]:
        raise ReleaseError(f"Conflicting destination tag {target['tag']}; tags are never overwritten")
    return True


def prepare(args, version):
    source_object = source_commit(args)
    require_push_ci(args, "core", args.core_sha)
    targets = []
    for branch, loader in TARGETS.items():
        sha = git(args.checkout, "rev-parse", f"refs/remotes/origin/{branch}")
        require_native_commit(args, branch, sha)
        target = {"branch": branch, "sha": sha, "tag": f"{version}-{loader}-{branch}"}
        tag_exists(args, target)
        targets.append(target)
    plan = {"schema": 1, "repository": args.repository, "source_tag": args.source_tag,
            "source_object": source_object, "core_sha": args.core_sha, "targets": targets}
    args.plan.parent.mkdir(parents=True, exist_ok=True)
    with args.plan.open("x", encoding="utf-8") as output:
        output.write(json.dumps(plan, indent=2) + "\n")
    print(json.dumps(plan, indent=2))


def publish(args, version):
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    if (set(plan) != {"schema", "repository", "source_tag", "source_object", "core_sha", "targets"}
            or type(plan["schema"]) is not int or plan["schema"] != 1 or plan["repository"] != args.repository
            or plan["source_tag"] != args.source_tag or plan["core_sha"] != args.core_sha
            or not isinstance(plan["targets"], list) or len(plan["targets"]) != len(TARGETS)):
        raise ReleaseError("Release plan does not match the requested source/repository")
    for target, (branch, loader) in zip(plan["targets"], TARGETS.items()):
        if (set(target) != {"branch", "sha", "tag"} or target["branch"] != branch
                or target["tag"] != f"{version}-{loader}-{branch}" or not SHA.fullmatch(target["sha"])):
            raise ReleaseError("Release plan must contain the four exact native tags and full commit SHAs")
    if source_commit(args) != plan["source_object"]:
        raise ReleaseError("Source tag changed after preparation")
    require_push_ci(args, "core", args.core_sha)
    existing = []
    for target in plan["targets"]:
        require_native_commit(args, target["branch"], target["sha"])
        git(args.checkout, "merge-base", "--is-ancestor", target["sha"],
            f"refs/remotes/origin/{target['branch']}")
        existing.append(tag_exists(args, target))
    token = os.environ.get("CORE_PIN_BOT_TOKEN")
    if not token or github_api("user", token)["login"] != "kuba6000-agent":
        raise ReleaseError("CORE_PIN_BOT_TOKEN must authenticate as kuba6000-agent")
    authentication = base64.b64encode(f"x-access-token:{token}".encode("utf-8")).decode("ascii")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::add-mask::{authentication}", flush=True)
    for target, exists in zip(plan["targets"], existing):
        if exists:
            print(f"Already published {target['tag']} at {target['sha']}", flush=True)
            continue
        git(args.checkout, "-c", f"http.https://github.com/.extraheader=AUTHORIZATION: basic {authentication}",
            "push", "--no-follow-tags", "origin", f"{target['sha']}:refs/tags/{target['tag']}")
        print(f"Published {target['tag']} at {target['sha']}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "publish"))
    parser.add_argument("--checkout", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-tag", required=True)
    parser.add_argument("--core-sha", required=True)
    parser.add_argument("--plan", type=Path, required=True)
    args = parser.parse_args()
    try:
        version = version_from_tag(args.source_tag)
        if not SHA.fullmatch(args.core_sha):
            raise ReleaseError("Core commit must be a full lowercase 40-character SHA-1")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
            raise ReleaseError("Repository must be owner/name")
        if args.command == "prepare":
            prepare(args, version)
        else:
            publish(args, version)
    except (ReleaseError, OSError, ValueError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
