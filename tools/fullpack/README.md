# Full modpack launchers

Run a pinned full modpack with the production mod built from this checkout. Requires Python3.10+
on `PATH` and the normal Java25/Node build prerequisites. Dedicated-server tasks ask once for
Minecraft EULA acceptance.
Gradle selects the game's Java runtime through its toolchains; Python launcher dependencies are
installed automatically in a local virtual environment.

From the repository root:

Linux (Bash):

```bash
./gradlew :forge-1.7.10:runFullPack
./gradlew :forge-1.7.10:runFullServer
```

Windows (PowerShell):

```powershell
.\gradlew.bat :forge-1.7.10:runFullPack
.\gradlew.bat :forge-1.7.10:runFullServer
```

Linux needs Python's virtual-environment and `ensurepip` support. On Debian/Ubuntu, install
`python3-venv` alongside Python3.10+ before the first launch. The client needs a graphical session
and working OpenGL drivers; the dedicated server can run without a desktop.

Replace `forge-1.7.10` with `forge-1.12.2`, `forge-1.20.1`, or
`neoforge-1.21.1`. The default packs are GTNH Daily824 (2026-11-08), Nomi-CEu1.7.7, Monifactory0.13.8 and ATM10 8.5,
respectively. Exact versions, files and hashes are recorded under `profiles/`. Normal launches do
not update these pins. Deleted or unavailable pinned downloads stop preparation with an error.

`runFullPack` builds and installs both the main mod and standalone icon generator, then opens the
client menu. `runFullServer` installs only the main mod and starts a dedicated server. Use
`prepareFullPack` or `prepareFullServer` to prepare without starting Minecraft. A supplied client
world appears in Singleplayer; select it there. No automated test suite is run by these commands.

Missing modpack files, configuration, scripts, Minecraft assets and loader libraries are downloaded
and cached. Every prepare/run replaces its own client or server game directory with a fresh copy.
Preparation prints its current stage, file/byte progress and elapsed activity during longer waits.
Loader installation keeps detailed output in the runtime cache's `installation.log` and reports
progress to the console. Gradle's task percentage is separate from download or installation progress.
The source world and download cache are retained. Client and server use separate owned directories;
a second process cannot reset an instance that is already running. These instances are independent
of Prism Launcher and personal account tokens.

Clients start muted under the offline name `FullpackTest`. Servers bind the game port to localhost,
disable whitelist enforcement and use offline mode. Our HTTP API has a generated administrator
password with localhost bypass disabled. Its default port is23240 on the server and23241 in the
client's local world; the HTTP listener binds all interfaces. Find its password in the instance's
`config/ae2webintegration/config.toml`. Our telemetry and update checker are disabled.

The default cache is `<checkout>/.gradle/fullpack`; prepared games are under
`runtimes/<profile>/<client-or-server>/game`. `stop` in the server console saves and shuts down;
Ctrl+C requests shutdown before enforcing the process deadline.

The first server preparation or launch asks for EULA acceptance. The answer is saved as
`eula=true` in `<fullpackHome>/eula.txt`, shared by all profiles and retained when instances reset.
Each prepared server receives its own `eula.txt`. For noninteractive use, accept once with
`-PfullpackAcceptEula=true` or `FULLPACK_ACCEPT_EULA=true`; later commands need no acceptance flag.
The Python CLI stores the same file beside its `--runtime-root` directory and accepts
`--accept-eula` for noninteractive use.

When sharing a checkout between Windows and Linux/WSL, use a separate `fullpackHome` for each
system, for example `-PfullpackHome="$HOME/.cache/ae2wi-fullpack"` in Bash. Installed Python/game
runtimes and saved launch commands contain platform-specific paths.

| Gradle property | Meaning |
| --- | --- |
| `fullpackHome` | Cache, Python environment and owned instances; default `.gradle/fullpack` |
| `fullpackProfile` | Profile directory name under `tools/fullpack/profiles` |
| `fullpackSlot` | Instance name; defaults to the selected profile |
| `fullpackWorld` | World directory containing `level.dat`, copied into the client/server instance |
| `fullpackJava` | Explicit game Java executable instead of Gradle's toolchain selection |
| `fullpackPython` | Python3.10+ executable used to create the launcher environment |
| `fullpackMemoryMiB` | Heap limit; default6144 for clients and4096 for servers |
| `fullpackPort` | Game server port; default25585 |
| `fullpackWebPort` | HTTP port; default23240 server /23241 client |
| `fullpackUser` | Offline client name; default `FullpackTest` |
| `fullpackJoinServer` | Optional localhost address for modern/1.12.2 multiplayer startup |

Relative paths are resolved from the repository root. Client tasks do not require EULA acceptance.
GTNH connects through Multiplayer → Direct Connect after the menu
loads; its Forge startup-join path is unsupported by these Gradle tasks.

Restart a prepared instance without resetting its world or settings:

Linux:

```bash
.gradle/fullpack/python/bin/python tools/fullpack/run_fullpack.py execute --runtime-root .gradle/fullpack/runtimes --profile monifactory-0.13.8 --role server
```

Windows:

```powershell
.gradle/fullpack/python/Scripts/python.exe tools/fullpack/run_fullpack.py execute --runtime-root .gradle/fullpack/runtimes --profile monifactory-0.13.8 --role server
```

Direct CLI `prepare`/`run` also accepts locked
content, runtime, JAR and world paths; use `--help` for arguments.

Run the launcher tests without starting Minecraft:

Linux:

```bash
.gradle/fullpack/python/bin/python -m unittest discover -s tools/fullpack -p 'test_*.py'
```

Windows:

```powershell
.gradle/fullpack/python/Scripts/python.exe -m unittest discover -s tools/fullpack -p "test_*.py"
```
