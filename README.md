# AE2 Web Integration — Minecraft 1.7.10

This branch contains the Forge 1.7.10 adapter for AE2 Web Integration, targeting the GTNH forks of AE2 and
AE2FluidCraft.

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

Build the optional generator with `./gradlew reobfIconGeneratorJar`. Install the resulting
`ae2webintegration-icon-generator-*.jar` without the `-dev` suffix in a client with AE2 and
GTNHLib 0.11.39 or newer. The web server mod is optional.

In a loaded world, `/ae2webicons export` exports registered items, their published variants and fluids
using the game's inventory renderer. If NEI is installed and enabled, its complete loaded catalogue is included.
The generator waits while NEI is loading. Arbitrary NBT combinations are not enumerated.

The resulting `AE2WebIntegration-icons-1.7.10-<packId>.ae2wi-icons` file is saved under `ae2webicons/`
in the game directory. It contains 64x64 icons grouped into atlas pages, resource keys and an export
report. Individual rendering failures are recorded and the remaining candidates are processed.
Progress is posted in chat every five seconds. After discovery, `/ae2webicons status` shows
processed and remaining icons with an approximate render time. Export prioritizes throughput and can
reduce the game's frame rate. Use `/ae2webicons cancel` to stop. Changing the world or reloading
resources cancels an active export.
