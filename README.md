# AE2 Web Integration — Minecraft 1.12.2

This branch contains the Forge 1.12.2 adapter for AE2 Web Integration, targeting AE2 UEL.

The shared, version-independent logic and full project documentation live on the
[`core` branch](https://github.com/kuba6000/AE2-Web-Integration/tree/core#readme). The `core` directory in
this branch is a Git submodule pinned to the exact revision tested with this adapter.

If you are building from source, clone the repository with submodules or initialize them after checkout:

```bash
git submodule update --init --recursive
```

If you only want to install the mod, download the appropriate JAR from the
[Releases page](https://github.com/kuba6000/AE2-Web-Integration/releases).

## Client icon export

Build the optional generator with `./gradlew reobfIconGeneratorJar`. Install
`ae2webintegration-icon-generator-*.jar` without the `-dev` suffix in a Forge 1.12.2
client with AE2 UEL and its required dependencies. The web server mod is optional.

In a loaded world, `/ae2webicons export` exports registered items, creative variants
and fluids using the current models and resource packs. If JEI is installed, its
full item and fluid catalogue is included. Arbitrary NBT combinations are not enumerated.
Use `/ae2webicons export nopbo` to disable PBO readback for that export; the default
uses PBO when supported. Both commands support completion in chat.

The resulting `AE2WebIntegration-icons-1.12.2-<packId>.ae2wi-icons` file is saved in
`ae2webicons/` under the game directory. It contains 64×64 icons, atlas pages,
resource keys and an export report. An individual rendering failure is recorded
and the remaining icons are processed.

A centered dialog displays progress over the dimmed world. Export prioritizes
throughput and refreshes the window roughly once per second during rendering;
an individual mod renderer can take longer. Gameplay controls stay blocked while
the world keeps running. Use **Cancel export**, Esc or `/ae2webicons cancel` to
stop, and **Done** to close the result. `/ae2webicons status` reports progress.
Changing worlds or reloading resources cancels an active export.

The exporter captures one still frame per icon. Partially transparent textures
drawn by nonblending cutout shaders may appear different against a web page's
background than they do in the game's inventory.

## IntelliJ IDEA

Open this checkout as a Gradle project. The versioned `.idea/inspectionProfiles` files select the
**AE2 Web Integration** project inspection profile. Gradle supplies the source sets and dependencies;
SDK locations and personal workspace settings stay local. Commit shared inspection changes with the code.

Framework entry points are configured in `gradle/idea-entry-points.xml`. Gradle sync
applies this policy to IDEA automatically; run `./gradlew updateIdeaEntryPoints`
to apply it explicitly. The task replaces only the `EntryPointsManager` component
in the ignored `.idea/misc.xml`, preserving SDK and other project settings. Edit
the versioned policy to change entry points; local changes to that component are
replaced on the next sync.
