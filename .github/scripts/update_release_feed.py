#!/usr/bin/env python3
"""Advance the JSON feed from published GitHub releases and commit changed files."""

import argparse
import json
import re
import subprocess
from datetime import datetime
from pathlib import Path


TARGETS = {"1.7.10": "forge", "1.12.2": "forge", "1.20.1": "forge", "1.21.1": "neoforge"}
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?")
ORDERED_PRE = re.compile(r"[a-zA-Z][a-zA-Z0-9-]*\.[0-9]+(?:\.[0-9]+)*")
TAG = re.compile(r"(.+)-(forge|neoforge)(-pre)?-(1\.7\.10|1\.12\.2|1\.20\.1|1\.21\.1)(-pre)?")


def git(repository, *args):
    return subprocess.run(["git", "-C", str(repository), *args],
                          capture_output=True, text=True, check=True).stdout.strip()


def version_key(value):
    match = VERSION.fullmatch(value)
    if not match:
        raise ValueError(f"Unsupported release version: {value}")
    if match[4] is not None and any(not part or re.fullmatch(r"0[0-9]+", part) for part in match[4].split(".")):
        raise ValueError(f"Invalid prerelease identifiers: {value}")
    if match[5] is not None and any(not part for part in match[5].split(".")):
        raise ValueError(f"Invalid build metadata: {value}")
    return tuple(int(match[i]) for i in (1, 2, 3)), match[4]


def newer(candidate, current):
    if current is None:
        return True
    left, left_pre = version_key(candidate["newest"])
    right, right_pre = version_key(current["newest"])
    if left != right:
        return left > right
    # Shipped descriptive/unnumbered prereleases have no reliable order within the same base.
    if not all(suffix and ORDERED_PRE.fullmatch(suffix) for suffix in (left_pre, right_pre)):
        return False
    def identifiers(suffix):
        return tuple((0, int(part)) if part.isdigit() else (1, part) for part in suffix.split("."))
    return identifiers(left_pre) > identifiers(right_pre)


def release_record(release):
    if release["draft"]:
        return None
    tag = release["tag_name"]
    match = TAG.fullmatch(tag)
    if not match or "snapshot" in tag.lower():
        return None
    newest, loader, pre_before, target, pre_after = match.groups()
    if TARGETS[target] != loader:
        return None
    _, suffix = version_key(newest)
    channel = "prerelease" if release["prerelease"] or pre_before or pre_after or suffix else "stable"
    assets = [asset for asset in release["assets"]
              if asset["name"] == f"ae2webintegration-{tag}.jar"
              and asset["state"] == "uploaded" and asset["size"] > 0]
    if not assets:
        return None
    if len(assets) != 1:
        raise ValueError(f"Ambiguous production JAR for {tag}")
    published = datetime.fromisoformat(release["published_at"].replace("Z", "+00:00"))
    if published.tzinfo is None:
        raise ValueError(f"Missing publication timezone for {tag}")
    return target, channel, {
        "newest": newest,
        "timestamp": int(published.timestamp()),
        "github_release_tag": tag,
        "github_release_url": release["html_url"],
        "github_release_download_url": assets[0]["browser_download_url"],
    }


def recommendation(records, current):
    by_tag = {current["github_release_tag"]: current} if current is not None else {}
    by_tag.update((record["github_release_tag"], record) for record in records)
    if not by_tag:
        return None
    newest_base = max(version_key(record["newest"])[0] for record in by_tag.values())
    candidates = [record for record in by_tag.values() if version_key(record["newest"])[0] == newest_base]
    candidates = [record for record in candidates if not any(newer(other, record) for other in candidates)]
    if len(candidates) == 1:
        return candidates[0]
    if current is not None:
        for record in candidates:
            if record["github_release_tag"] == current["github_release_tag"]:
                return record
    raise ValueError("Ambiguous release order; use numbered prereleases or choose the feed record manually: "
                     + ", ".join(record["github_release_tag"] for record in candidates))


def update(repository, releases, author_name, author_email):
    if git(repository, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Version worktree must be clean")
    feeds = {}
    for target in TARGETS:
        path = repository / f"{target}.json"
        feed = json.loads(path.read_text(encoding="utf-8"))
        if feed["version"] != target:
            raise ValueError(f"Incorrect Minecraft target in {path}")
        feeds[target] = feed
    original = json.dumps(feeds, sort_keys=True)
    records = [record for release in releases if (record := release_record(release)) is not None]
    for target, feed in feeds.items():
        channels = feed["releases"]
        # Stable first, to avoid introducing superseded historical pre releases into empty channels.
        for channel in ("stable", "prerelease"):
            candidates = [record for mc, kind, record in records if mc == target and kind == channel]
            if channel == "prerelease" and channels[channel] is None and channels["stable"] is not None:
                stable_base = version_key(channels["stable"]["newest"])[0]
                candidates = [record for record in candidates if version_key(record["newest"])[0] > stable_base]
            channels[channel] = recommendation(candidates, channels[channel])
    if json.dumps(feeds, sort_keys=True) == original:
        print("Release feed is already current")
        return
    for target, feed in feeds.items():
        path = repository / f"{target}.json"
        # Leave unchanged files byte-for-byte intact, including their formatting.
        if json.loads(path.read_text(encoding="utf-8")) != feed:
            path.write_text(json.dumps(feed, indent=2) + "\n", encoding="utf-8")
            git(repository, "add", "--", path.name)
    git(repository, "-c", f"user.name={author_name}", "-c", f"user.email={author_email}",
        "-c", "commit.gpgsign=false", "commit", "-m", "Update published release feed")
    print("Committed updated release feed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--releases", type=Path, required=True)
    parser.add_argument("--author-name", required=True)
    parser.add_argument("--author-email", required=True)
    args = parser.parse_args()
    update(args.repository, json.loads(args.releases.read_text(encoding="utf-8")),
           args.author_name, args.author_email)


if __name__ == "__main__":
    main()
