#!/usr/bin/env python3
"""Stage the exact Gradle output for independent release destinations."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import re
import subprocess
import tempfile
from urllib.parse import quote


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def verify_artifact(manifest, artifact):
    content = artifact.read_bytes()
    if hashlib.sha512(content).hexdigest() != manifest["sha512"]:
        raise ValueError("Production JAR differs from the verified Gradle output")
    if hashlib.sha1(content).hexdigest() != manifest["sha1"]:
        raise ValueError("Production JAR SHA-1 differs from the verified Gradle output")


def artifacts(manifest):
    return [manifest, *manifest.get("additional_artifacts", [])]


def stage(manifest_path, metadata_path, output):
    manifest = read_json(manifest_path)
    metadata = read_json(metadata_path)
    for field in ("tag", "minecraft", "loader", "release_type"):
        if manifest[field] != metadata[field]:
            raise ValueError(f"Build and release notes disagree on {field}")
    notes = Path(manifest["notes_file"])
    names = set()
    for entry in artifacts(manifest):
        artifact = Path(entry["artifact"])
        verify_artifact(entry, artifact)
        if artifact.name in names:
            raise ValueError("Release artifacts have duplicate filenames")
        names.add(artifact.name)
    if not notes.read_text(encoding="utf-8").strip():
        raise ValueError("Release description is empty")
    output.mkdir(parents=True, exist_ok=True)
    for entry in artifacts(manifest):
        artifact = Path(entry["artifact"])
        shutil.copyfile(artifact, output / artifact.name)
        entry["artifact"] = artifact.name
    shutil.copyfile(notes, output / "notes.md")
    manifest["notes_file"] = "notes.md"
    metadata["notes_file"] = "notes.md"
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(f"Staged {len(names)} verified release JARs")


def bundle_file(bundle, name):
    if not re.fullmatch(r"[A-Za-z0-9_.+-]+", name) or Path(name).name != name:
        raise ValueError("Bundle files must be directly inside the bundle directory")
    path = bundle / name
    if path.resolve().parent != bundle.resolve():
        raise ValueError("Bundle file escapes the bundle directory")
    return path


def export(bundle, github_output):
    manifest = read_json(bundle / "manifest.json")
    artifact = bundle_file(bundle, manifest["artifact"])
    notes = bundle_file(bundle, manifest["notes_file"])
    for entry in artifacts(manifest):
        verify_artifact(entry, bundle_file(bundle, entry["artifact"]))
    outputs = {"artifact": str(artifact.resolve()), "notes": str(notes.resolve()), "sha512": manifest["sha512"],
               "manifest": str((bundle / "manifest.json").resolve()), "release_type": manifest["release_type"]}
    with github_output.open("a", encoding="utf-8") as stream:
        for key, value in outputs.items():
            if "\n" in value or "\r" in value:
                raise ValueError("Invalid multiline workflow output")
            stream.write(f"{key}={value}\n")


def github(bundle, repository, publish):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Expected owner/repository")
    manifest = read_json(bundle / "manifest.json")
    metadata = read_json(bundle / "metadata.json")
    tag = manifest["tag"]
    if tag != metadata["tag"]:
        raise ValueError("Bundle metadata disagrees with the built tag")
    artifact = bundle_file(bundle, manifest["artifact"])
    notes = bundle_file(bundle, manifest["notes_file"])
    uploads = [(entry, bundle_file(bundle, entry["artifact"])) for entry in artifacts(manifest)]
    for entry, upload in uploads:
        verify_artifact(entry, upload)
    description = notes.read_text(encoding="utf-8")
    if not description.strip():
        raise ValueError("Release description is empty")
    if not publish:
        print(f"GitHub preview: {repository} / {tag} / {', '.join(upload.name for _, upload in uploads)}")
        return

    def gh(*args):
        return subprocess.run(["gh", *args], check=True, capture_output=True, text=True, encoding="utf-8")

    try:
        existing = json.loads(gh("api", f"repos/{repository}/releases/tags/{quote(tag, safe='')}").stdout)
    except subprocess.CalledProcessError as error:
        if "(HTTP 404)" not in error.stderr:
            raise
        existing = None

    if existing is None:
        arguments = ["release", "create", tag, "--repo", repository, "--verify-tag", "--draft",
                     "--title", tag, "--notes-file", str(notes), *(str(upload) for _, upload in uploads)]
        if metadata["prerelease"]:
            arguments.append("--prerelease")
        gh(*arguments)
        gh("release", "edit", tag, "--repo", repository, "--draft=false")
        print(f"Created GitHub release {tag}")
        return

    if existing["body"].strip() != description.strip() or existing["prerelease"] != metadata["prerelease"]:
        raise ValueError("Existing release description or channel differs; review it before retrying")
    for entry, upload in uploads:
        matching = [asset for asset in existing["assets"] if asset["name"] == upload.name]
        if len(matching) > 1:
            raise ValueError("Existing release contains ambiguous production assets")
        if matching:
            with tempfile.TemporaryDirectory() as directory:
                gh("release", "download", tag, "--repo", repository, "--pattern", upload.name, "--dir", directory)
                verify_artifact(entry, Path(directory) / upload.name)
        else:
            gh("release", "upload", tag, str(upload), "--repo", repository)
    if existing["draft"]:
        gh("release", "edit", tag, "--repo", repository, "--draft=false")
    print(f"Verified GitHub release {tag}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    staging = commands.add_parser("stage")
    staging.add_argument("--manifest", type=Path, required=True)
    staging.add_argument("--metadata", type=Path, required=True)
    staging.add_argument("--output", type=Path, required=True)
    publishing = commands.add_parser("github")
    publishing.add_argument("--bundle", type=Path, required=True)
    publishing.add_argument("--repository", required=True)
    publishing.add_argument("--publish", action="store_true", help="Publish; omitted means offline preview")
    exporting = commands.add_parser("export")
    exporting.add_argument("--bundle", type=Path, required=True)
    exporting.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "stage":
        stage(args.manifest, args.metadata, args.output)
    elif args.command == "github":
        github(args.bundle, args.repository, args.publish)
    elif args.command == "export":
        export(args.bundle, args.github_output)


if __name__ == "__main__":
    main()
