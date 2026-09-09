#!/usr/bin/env python3

import argparse
import re
import subprocess
import sys
from pathlib import Path


class UpdateError(RuntimeError):
    pass


def git(repository, *arguments):
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        details = result.stderr.strip() or result.stdout.strip()
        raise UpdateError(f"git {' '.join(arguments)} failed: {details}")
    return result.stdout.strip()


def parse_arguments():
    parser = argparse.ArgumentParser(description="Commit an updated core submodule pin")
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--core-sha", required=True)
    parser.add_argument("--author-name", required=True)
    parser.add_argument("--author-email", required=True)
    return parser.parse_args()


def update_core_pin(repository, core_sha, author_name, author_email):
    repository = repository.resolve()
    core_sha = core_sha.lower()

    if not re.fullmatch(r"[0-9a-f]{40}", core_sha):
        raise UpdateError("core SHA must be a full 40-character SHA-1")

    git(repository, "rev-parse", "--show-toplevel")
    git(repository, "cat-file", "-e", f"{core_sha}^{{commit}}")

    status = git(repository, "status", "--porcelain", "--untracked-files=all")
    if status:
        raise UpdateError("target worktree must be clean before updating the core pin")

    index_entry = git(repository, "ls-files", "--stage", "--", "core")
    match = re.fullmatch(r"(\d{6}) ([0-9a-f]{40}) 0\tcore", index_entry)
    if match is None or match.group(1) != "160000":
        raise UpdateError("core must be a tracked gitlink")

    previous_sha = match.group(2)
    if previous_sha == core_sha:
        print(f"core is already pinned to {core_sha}")
        return False

    git(repository, "update-index", "--cacheinfo", f"160000,{core_sha},core")
    staged_paths = git(repository, "diff", "--cached", "--name-only").splitlines()
    if staged_paths != ["core"]:
        raise UpdateError("updating the pin must stage exactly the core gitlink")

    git(
        repository,
        "-c",
        f"user.name={author_name}",
        "-c",
        f"user.email={author_email}",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-m",
        f"Update core pin to {core_sha[:12]}",
    )
    print(f"updated core pin from {previous_sha} to {core_sha}")
    return True


def main():
    arguments = parse_arguments()
    try:
        update_core_pin(
            arguments.repository,
            arguments.core_sha,
            arguments.author_name,
            arguments.author_email,
        )
    except UpdateError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
