# Release descriptions

Each release uses the same composed Markdown on GitHub, Modrinth and CurseForge, in this order:

1. Optional `<version>.md`: an introduction shared by that mod version, such as `2.0.0.md` or `2.0.0-beta.1.md`.
2. Optional `<full-tag>.md`: an addition for one target, such as `2.0.0-forge-1.20.1.md`.
3. The generated changelog.
4. Optional `common.md`: a footer repeated on every release until removed.

Use UTF-8 for authored files. Missing or empty files are omitted. For example, `2.0.0.md`
could contain a short feature introduction and any required user actions. If telemetry is introduced
later, `common.md` can explain what it sends, its purpose and how users control it. Add that notice
only when it describes implemented behavior.

For a short footer in smaller text, `common.md` can use inline `<sub>` markup, optionally below a divider:

```markdown
---

<sub>Telemetry disclosure and details go here.</sub>
```

The helper preserves this Markdown/HTML rather than adding formatting automatically. Each portal
controls its rendered appearance, so text sizing may differ.

Tags use a SemVer mod version followed by the loader and Minecraft version:

| Minecraft | Loader | Example tag |
| --- | --- | --- |
| 1.7.10 | forge | `2.0.0-forge-1.7.10` |
| 1.12.2 | forge | `2.0.0-forge-1.12.2` |
| 1.20.1 | forge | `2.0.0-forge-1.20.1` |
| 1.21.1 | neoforge | `2.0.0-neoforge-1.21.1` |

`2.0.0-alpha.1-forge-1.20.1` is alpha; beta, rc and other SemVer prereleases are beta. Legacy
`-pre` markers before or after the Minecraft version are also prereleases. Stable tags are release.
Tags containing `snapshot` are prereleases and skip Modrinth and CurseForge publication.

To prepare notes locally, use Python 3.10+, Git with full tag/history data, and an authenticated GitHub
CLI (`gh`). Choose the table row matching the checked-out Minecraft branch and use the same full tag
throughout preparation and building. All relative paths are resolved against the script's checkout:

```sh
python .github/scripts/release_notes.py --tag 2.0.0-forge-1.20.1 --minecraft 1.20.1 --loader forge --repository kuba6000/AE2-Web-Integration --output build/release/notes.md
```

The helper uses the nearest non-snapshot release tag for the same Minecraft/loader pair along the
current tag's first-parent history, or HEAD for a proposed tag. It passes that tag explicitly to
GitHub's generated-notes API. A first release with no matching predecessor uses the local first-parent
commit history so GitHub cannot choose a predecessor from another Minecraft line. No release or tag
is created by this command.

For an offline preview, add `--changelog-file path/to/generated-changes.md`. The composed file is
written to the requested output path, with `metadata.json` beside it. Metadata records the full tag,
SemVer component, target, loader, release type, snapshot/prerelease flags and previous tag. The optional
`--github-output` argument appends the version, release type, flags and any previous tag for Actions.
Build `VERSION` remains the full tag.

After preparing the notes, preview the build and both publishers without portal credentials. Use
`--release-type release`, `alpha` or `beta` to match the tag:

```sh
VERSION=2.0.0-forge-1.20.1 python .github/scripts/release_preview.py --minecraft 1.20.1 --release-type release --notes build/release/notes.md
```

On Windows PowerShell:

```powershell
$env:VERSION = '2.0.0-forge-1.20.1'
python .github/scripts/release_preview.py --minecraft 1.20.1 --release-type release --notes build/release/notes.md
```

The wrapper builds the release JARs and runs both real publisher plugins in debug mode. It supplies
synthetic CurseForge version IDs through a temporary local metadata server that rejects upload
requests. This checks the artifacts and preview payloads; it does not validate remote authentication,
real CurseForge metadata IDs or portal acceptance.

`releasePreview` writes `build/release/manifest.json` with the selected release files, target metadata
and hashes. The workflow stages these exact JARs, composed notes and metadata in a verified bundle;
each destination publishes those same files.

Push a release tag to run `Release tagged build` on its target branch. It builds and verifies the
files before the independent GitHub, Modrinth and CurseForge jobs publish them. The main server mod,
separate client icon-generator JARs and sources are attached to the same release. The icon generator
is not embedded in the server mod.

CurseForge additional files inherit the main file's game, loader and Server environment tags; they
cannot declare separate environment tags. The generator's display name identifies it as an
`icon generator (client tool)`.

If a destination job fails, use **Re-run failed jobs** in the original GitHub Actions run to retry the
failed destination with the retained verified bundle.

To check preparation before tagging, run `Preview release` from the `core` branch. Select the
Minecraft target, optionally choose a ref (the target branch is the default), and provide the proposed
full tag. This preview builds the release files, exercises both publishers in debug mode and retains
the verified release bundle without publishing it. Portal uploads require `MODRINTH_TOKEN` and
`CURSEFORGE_TOKEN` repository secrets; debug previews do not establish portal acceptance.
