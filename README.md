# AE2 Web Integration — Minecraft 1.21.1

This branch contains the NeoForge 1.21.1 adapter for AE2 Web Integration.

The shared, version-independent logic and full project documentation live on the
[`core` branch](https://github.com/kuba6000/AE2-Web-Integration/tree/core#readme). The `core` directory in
this branch is a Git submodule pinned to the exact revision tested with this adapter.

If you are building from source, clone the repository with submodules or initialize them after checkout:

```bash
git submodule update --init --recursive
```

If you only want to install the mod, download the appropriate JAR from the
[Releases page](https://github.com/kuba6000/AE2-Web-Integration/releases).

## IntelliJ IDEA

Open this checkout as a Gradle project. The versioned `.idea/inspectionProfiles` files select the
**AE2 Web Integration** project inspection profile. Gradle supplies the source sets and dependencies;
SDK locations and personal workspace settings stay local. Commit shared inspection changes with the code.
