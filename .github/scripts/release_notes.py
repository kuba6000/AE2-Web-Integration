#!/usr/bin/env python3
"""Compose version introductions, generated GitHub changes and a shared footer."""

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path


TARGETS = {"1.7.10": "forge", "1.12.2": "forge", "1.20.1": "forge", "1.21.1": "neoforge"}
TAG = re.compile(r"(.+)-(forge|neoforge)(-pre)?-(1\.7\.10|1\.12\.2|1\.20\.1|1\.21\.1)(-pre)?")
VERSION = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?"
)


def parse_tag(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError("Tag must include a release version, loader and supported Minecraft target")
    version, loader, pre_before, minecraft, pre_after = match.groups()
    parsed = VERSION.fullmatch(version)
    if not parsed:
        raise ValueError(f"Unsupported SemVer release version: {version}")
    if parsed[4] is not None and any(not part or re.fullmatch(r"0[0-9]+", part)
                                     for part in parsed[4].split(".")):
        raise ValueError(f"Invalid prerelease identifiers: {version}")
    if parsed[5] is not None and any(not part for part in parsed[5].split(".")):
        raise ValueError(f"Invalid build metadata: {version}")
    if TARGETS[minecraft] != loader:
        raise ValueError(f"Minecraft {minecraft} requires {TARGETS[minecraft]}")
    return version, loader, pre_before, minecraft, pre_after


def git(checkout, *arguments):
    return subprocess.run(["git", "-C", str(checkout), *arguments], check=True,
                          capture_output=True, encoding="utf-8").stdout.strip()


def previous_tag(checkout, current, minecraft, loader):
    refs = git(checkout, "for-each-ref", "--sort=-version:refname",
               "--format=%(refname:strip=2)%09%(*objectname)%09%(objectname)", "refs/tags")
    candidates = {}
    anchor = git(checkout, "rev-parse", "HEAD")
    for line in refs.splitlines():
        tag, peeled, direct = line.split("\t")
        commit = peeled or direct
        if tag == current:
            anchor = commit
            continue
        try:
            _, tag_loader, _, tag_minecraft, _ = parse_tag(tag)
        except ValueError:
            continue
        if (tag_minecraft, tag_loader) == (minecraft, loader) and "snapshot" not in tag.lower():
            candidates.setdefault(commit, tag)
    for commit in git(checkout, "rev-list", "--first-parent", anchor).splitlines():
        if commit in candidates:
            return candidates[commit], anchor
    return None, anchor


def generated_notes(args, checkout, previous, anchor):
    if args.changelog_file:
        return (checkout / args.changelog_file).read_text(encoding="utf-8")
    if not previous:
        return "## Changes\n\n" + git(checkout, "log", "--first-parent", "--format=- %s (%h)", anchor, "--")
    payload = {"tag_name": args.tag, "target_commitish": anchor, "previous_tag_name": previous}
    response = subprocess.run(
        [shutil.which("gh") or "gh", "api", "--method", "POST", "-H", "Accept: application/vnd.github+json",
         f"repos/{args.repository}/releases/generate-notes", "--input", "-"],
        input=json.dumps(payload), check=True, capture_output=True, encoding="utf-8", cwd=checkout,
    )
    return json.loads(response.stdout)["body"]


def prepare(args, checkout):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository) or any(
            part in (".", "..") for part in args.repository.split("/")):
        raise ValueError("--repository must be a GitHub owner/repository name")
    version, loader, pre_before, minecraft, pre_after = parse_tag(args.tag)
    if (minecraft, loader) != (args.minecraft, args.loader):
        raise ValueError("Tag Minecraft target and loader must match --minecraft and --loader")
    previous, anchor = previous_tag(checkout, args.tag, minecraft, loader)
    suffix = version.partition("+")[0].partition("-")[2]
    snapshot = "snapshot" in args.tag.lower()
    prerelease = bool(suffix or pre_before or pre_after or snapshot)
    release_type = "release"
    if prerelease:
        release_type = "alpha" if re.match(r"alpha(?:[.-]|$)", suffix, re.IGNORECASE) else "beta"
    sections = []
    for name in (version, args.tag):
        path = checkout / ".releases" / f"{name}.md"
        if path.is_file():
            sections.append(path.read_text(encoding="utf-8").strip("\n"))
    sections.append(generated_notes(args, checkout, previous, anchor).replace("\r\n", "\n").strip("\n"))
    footer = checkout / ".releases" / "common.md"
    if footer.is_file():
        sections.append(footer.read_text(encoding="utf-8").strip("\n"))
    output = (checkout / args.output).resolve()
    metadata = {
        "tag": args.tag, "version": version, "minecraft": minecraft, "loader": loader,
        "release_type": release_type, "snapshot": snapshot, "prerelease": prerelease,
        "previous_tag": previous, "notes_file": str(output),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n\n".join(section for section in sections if section.strip()) + "\n",
                      encoding="utf-8", newline="\n")
    output.with_name("metadata.json").write_text(json.dumps(metadata, indent=2) + "\n",
                                                 encoding="utf-8", newline="\n")
    if args.github_output:
        with (checkout / args.github_output).open("a", encoding="utf-8", newline="\n") as stream:
            for key in ("release_type", "snapshot", "prerelease", "version", "previous_tag"):
                value = metadata[key]
                if value is not None:
                    stream.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--minecraft", required=True, choices=TARGETS)
    parser.add_argument("--loader", required=True, choices=("forge", "neoforge"))
    parser.add_argument("--repository", required=True, help="GitHub owner/repository")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--changelog-file", type=Path, help="Use offline generated Markdown instead of GitHub")
    parser.add_argument("--github-output", type=Path, help="Append validated single-line workflow outputs")
    args = parser.parse_args()
    try:
        prepare(args, Path(__file__).resolve().parents[2])
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
