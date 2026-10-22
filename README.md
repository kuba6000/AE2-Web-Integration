<div align="center">

# AE2 Web Integration

[![CF Downloads](https://cf.way2muchnoise.eu/full_1122761_downloads.svg)](https://www.curseforge.com/minecraft/mc-mods/ae2-web-integration)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/8MGTfmHF?logo=modrinth)](https://modrinth.com/mod/ae2-web-integration)

</div>

An AE2 add-on that lets you access your terminal from a web browser, wherever you are! You can browse your
network, submit crafting requests, view and cancel crafting jobs, track their progress, and even send updates
through a Discord webhook.

**Now works with multiple networks and on public servers, where every player can have their own account!**

**THIS MOD SHOULD ONLY BE INSTALLED ON THE SERVER. IT ADDS NO ITEMS OR BLOCKS!**

## Showcase on YouTube

### Main mod

[![Main mod showcase](https://img.youtube.com/vi/3uey6nuW09g/0.jpg)](https://www.youtube.com/watch?v=3uey6nuW09g)

### Discord integration

[![Discord integration showcase](https://img.youtube.com/vi/e4a86dBz7NY/0.jpg)](https://www.youtube.com/watch?v=e4a86dBz7NY)

## How does it work?

The mod starts an HTTP server when the Minecraft server starts. This server hosts the built-in web panel and
exposes the API used by that panel.

### How does the web panel know which network to use?

Because the mod is server-side only, it does not add another terminal or block for selecting a network.

On modern Minecraft versions, networks are identified through Wireless Access Points and their owners. Every
player who wants to access a network through the website must place their own Wireless Access Point on that
network.

On older Minecraft versions, networks are identified through AE2 Security Terminals. Players need a biometric
card in the Security Terminal with the required permissions. Wild biometric cards are ignored for security
reasons.

The `Admin` web account can access every available network on the server: networks with at least one Wireless
Access Point on modern versions, and networks with a Security Terminal on older versions. Protect the admin
password accordingly!

## Current features

### Optional item icons

To show item icons in the web panel, generate an icon pack from your Minecraft client:

1. Install the `ae2webintegration-icon-generator-*.jar` for your Minecraft version and mod loader
   in your client's `mods` folder. Use the same modpack as the server.
2. Start the game with the resource packs you want to use for the icons, then enter a world.
3. Run `/ae2webicons export` in chat and wait for the progress window to finish.
4. Find the generated `.ae2wi-icons` file in the `ae2webicons` folder inside your game directory.
5. Stop the server and copy that file into `config/ae2webintegration/`. Keep its `.ae2wi-icons` extension;
   the filename can stay unchanged.
6. Start the server. In the web panel settings, choose **Icons and names** or **Icons only**
   under **Terminal display**.

The icons use the appearance of your loaded resource packs. You can cancel generation from the
progress window. To replace or remove the server's icon pack, stop the server first and restart it afterward.
Keep only one `.ae2wi-icons` file in that folder. If several are present, the server logs their names
and disables icons until the conflict is resolved and the server is restarted.
Icons are optional; without an icon pack, the terminal displays item names.

### Terminal

- Browse, sort, and filter the contents of AE2 networks
- Monitor as many networks as you want
- Public mode for servers with multiple independent players
- Submit new crafting requests, with automatic or manual CPU selection
- View the status of every crafting CPU
- Cancel crafting jobs
- Track active and completed crafting jobs
- Send crafting updates through a Discord webhook

### Crafting plan images

After calculating a plan, choose **Export PNG** next to **Start** in the default theme. Choose the
grid layout, tile contents, ordering and background, then download a single PNG. The export includes
the full plan independently of the current search and works without an icon pack. Settings and the
image proportions are remembered for the next export; very large images use a lower resolution to fit.

### Web themes

Choose a theme in **Web Settings → Choose a theme**. To install one for everyone, place its
ZIP in `config/ae2webintegration/themes/` and restart the server. Set `general.default_theme` in
`config/ae2webintegration/config.toml` to `server:<theme id>` to make it the server default, or
`builtin:default` for the built-in UI. Users can follow that default or keep their own browser choice.

If a theme prevents normal use, open the page with `?safe=1` before its `#` fragment to
use the built-in UI.

## Gallery

<details>
<summary>Gallery</summary>

<img width="2560" height="1440" alt="AE2 Web Integration terminal" src="https://github.com/user-attachments/assets/9363c6c4-26dd-46fe-a6c2-84111338e6b0" />
<img width="737" height="70" alt="AE2 Web Integration status display" src="https://github.com/user-attachments/assets/2d95024e-25ca-415f-a63d-945f2c906302" />
<img width="2560" height="1440" alt="AE2 Web Integration crafting view" src="https://github.com/user-attachments/assets/c880a117-75d3-4d53-9ebf-db67135a3275" />
<img width="2560" height="1440" alt="AE2 Web Integration crafting job view" src="https://github.com/user-attachments/assets/2aeb72af-7abf-4cad-9a7a-1f66bd243594" />

</details>

## Security

### Public mode

In public mode, every player can create their own web account and access the AE2 networks they are allowed to
use. Registration starts on the website and must be confirmed in game with the command shown by the web panel.
The player must be online to register.

Player passwords are stored as salted PBKDF2-HMAC-SHA1 hashes. Sessions expire after one hour without activity
and can be extended by use for up to twelve hours from login. **Remember me** survives server restarts,
expires after seven days without activity and can be extended for up to sixty days from login. Watching a
live terminal or CPU counts as activity. Changing the password revokes existing sessions.
Each account can keep twenty remembered sessions; another remembered login signs out the oldest one.

### Public mode disabled

When public mode is disabled, only the `Admin` account is available. Its password is configured in the mod's
config file. The session mechanism is the same as in public mode.

### Localhost access

Connections from the loopback address (`127.0.0.1`/`localhost`) are authenticated as admin by default. This is
controlled by `allow_no_password_on_localhost`. When a reverse proxy is used, this rule applies to the client
address resolved from the proxy headers.

## Reverse proxy setup

The mod has built-in support for resolving the real client IP behind a reverse proxy. The resolved address is
used consistently for both the localhost authentication rule and per-IP rate limiting.

The mod only reads `X-Forwarded-For` or `X-Real-IP` when the direct TCP connection comes from:

- a proxy running on the same machine as the Minecraft server; or
- an address or network listed in `trusted_proxies`.

A proxy on the same machine is trusted automatically and does not need to be added to the config. For a proxy
running on another machine, set `trusted_proxies` to a comma-separated list of literal IPv4/IPv6 addresses or
CIDR ranges. For example, in the TOML config:

```toml
[general]
trusted_proxies = '192.168.1.10, 10.20.0.0/24, 2001:db8::10'
```

Hostnames are not accepted; use a literal address or CIDR range instead.

`X-Forwarded-For` takes priority over `X-Real-IP`. For a chain such as `client, proxy1, proxy2`, the mod walks
from right to left, skips configured trusted proxies, and uses the first untrusted address as the client. This
prevents a client from granting itself localhost access by sending a forged
`X-Forwarded-For: 127.0.0.1` header directly.

For HTTPS termination, preserve the public `Host` header (including any nonstandard port) and set
`X-Forwarded-Proto` to the public protocol, `http` or `https`. The mod accepts this protocol header only
from the same trusted proxies described above. It is used to validate the origin of browser login and
registration forms when `Sec-Fetch-Site` is absent. The proxy must overwrite `X-Forwarded-Proto`, not
forward an arbitrary client value or append a list. With multiple proxy hops, the last trusted proxy
must supply the verified public protocol.

Example Nginx configuration for a proxy running on the same machine:

```nginx
location / {
    proxy_pass http://127.0.0.1:2324;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

### Hosting through another web server

The [`tools/php-proxy`](tools/php-proxy) directory contains a PHP reverse proxy that serves
the mod's website, assets and API through an existing web server.

## Requirements

- A compatible AE2 installation for one of the supported Minecraft versions
- An available TCP port for the built-in web server
- A firewall/NAT rule if you want to reach the panel from outside your local network

By default, the panel is available at `http://your-server-ip-or-domain:2324/`. The port is configurable.

## How to use

1. Download the latest JAR for your Minecraft version from the
   [Releases page](https://github.com/kuba6000/AE2-Web-Integration/releases).
2. Drop the mod into the server's `mods` folder. On multiplayer, it only belongs on the server. It also works in
   a single-player instance, although that is not its main use case.
3. Start the server once to generate the config.
4. Open `config/ae2webintegration/config.toml`. Configure the port, admin
   password, public mode, and other settings as needed.
5. **Disable public mode if you are playing alone.**
6. Reload the config with `/ae2webintegration reload`, or restart the server.
7. Allow the configured port through your firewall and router as needed, or route access through a reverse
   proxy.
8. Visit `http://your-server-ip-or-domain:configured-port/` and log in with the `Admin` account and the password
   from the config.

In public mode, a player can create an account from the login page. After choosing a password, the page displays
an `/ae2webintegration auth <token>` command. The player must run that command in game to finish registration.

Enable **Record crafting history** in a network's settings to track its crafting jobs. Enable
**Include machine-requested crafting** there to also track jobs started by machines on that network.
The machine option applies to new jobs. Enabling tracking does not reconstruct an ongoing job's history.

Completed and cancelled jobs are saved with the world and remain available after a server restart.
Choose crafting history retention in the network settings. The default is 7 days, with a limit of
1,000 jobs per network. Administrators can change the retention bounds and job limit in `crafting_history`.

### Resource history

Network settings have independent switches for input/output history and periodic storage quantities.
Choose each network's storage sampling interval, input/output interval and history retention there,
within the server's allowed ranges. Input/output recording alone does not scan the inventory;
accepted input includes resources consumed by void storage. Select a resource in the terminal and open
**Resource history** in its details to view measurements.

Input/output history saves aggregated totals every 60 seconds by default; storage sampling defaults
to five minutes. Recent terminal summaries use five-second samples over
two minutes independently of saved history. Charts show each measured interval's actual span and
retain available partial counts when collection has gaps.

Flow also records the number of accepted input and output operations. Recent summaries show an
approximate rate per second when the measured interval is complete. One transfer of 64 items counts
as one operation; these counts describe network transfers, not individual machine recipes.

History is stored in an embedded SQLite database at `ae2webintegration/grids.sqlite` in the world directory.
No database installation is needed. Server startup fails if the database cannot be initialized.
The defaults retain up to 7 days and 250,000 records per network;
the oldest records are removed when either limit is reached. One older stock measurement per resource
may remain as a dated baseline, within the same record limit. Configure minimum, maximum and default
values, plus the record cap, under `resource_history` in `config.toml` and apply them with `/ae2webintegration reload`
or a server restart.
Existing network choices outside new bounds are adjusted to the nearest bound. The record cap can
shorten the selected retention and is not a byte limit. Expired data is hidden from history queries;
physical cleanup runs during database initialization, writes and periodic background maintenance.
Missing measurements are shown as gaps, not zero activity.

With automatic refresh enabled, the terminal receives live changes over SSE. Live viewing does not
require history tracking or increase the frequency of saved storage measurements. After a connection
interruption or storage topology change, the terminal loads a fresh snapshot automatically.

## Notification services integration

**Integrations only works when public mode is disabled.**

Crafting completion notifications can be filtered with `minimum_crafting_duration_seconds` and
`minimum_crafting_amount`. A notification must meet both configured minimums. Both values default to `0`,
which keeps all crafting completion notifications enabled.
Notifications are initialized ONLY for tracked grids

#### Discord Integration

Create a Discord webhook and set its URL as `discord_webhook` in the AE2 Web Integration config. You can also
set `discord_role_id` if a role should be pinged on errors.


<img width="467" height="224" alt="AE2 Web Integration Discord message" src="https://github.com/user-attachments/assets/f9f7635d-676c-40a3-8334-f7fa35e5867a" />

#### NTFY Integration

[ntfy](https://ntfy.sh/) sends the same crafting updates as a phone or desktop notification. Set `ntfy.host` to [ntfy.sh](https://ntfy.sh/) or to your own server, and set `ntfy.topic` to a topic you subscribed to in the app. A host without `http://` or `https://` is sent over HTTPS.

Leave `ntfy.user` and `ntfy.password` empty when the topic is public. A private topic needs both. Setting only one of them turns ntfy off.

`notifications.full_domain` is optional. When it is set, opening the notification goes to that address.

## Compatibility

The mod currently supports Minecraft 1.21.1 (NeoForge), 1.20.1 (Forge), 1.12.2 (Forge), and 1.7.10 (Forge/GTNH).
Builds for different versions are not interchangeable.

A few additional compatibility notes:

- The 1.7.10 build targets the GTNH forks of
  [AE2](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial) and
  [AE2FC](https://github.com/GTNewHorizons/AE2FluidCraft-Rework).
- The 1.12.2 build targets [AE2 UEL](https://github.com/AE2-UEL/Applied-Energistics-2) and
  [AE2FC for 1.12.2](https://github.com/AE2-UEL/AE2FluidCraft-Rework/).
- The 1.20.1 and 1.21.1 builds include optional
  [AdvancedAE](https://www.curseforge.com/minecraft/mc-mods/advancedae) integration.

## Development

### Repository architecture

The [`core`](https://github.com/kuba6000/AE2-Web-Integration/tree/core) branch contains the shared,
version-independent logic: the HTTP server, web API, authentication, configuration
definitions, crafting and tracking logic, Discord integration, and the web assets.

The Minecraft-version branches are thin adapters around that shared core. They contain only the code that has
to know about a specific Minecraft version, mod loader, or AE2 API: lifecycle hooks, config and command wiring,
Mixins, and conversions between AE2 objects and the interfaces understood by core.

| Branch | Purpose |
| --- | --- |
| [`core`](https://github.com/kuba6000/AE2-Web-Integration/tree/core) | Shared, version-independent logic and this documentation. **It is not a standalone Minecraft mod.** |
| [`1.21.1`](https://github.com/kuba6000/AE2-Web-Integration/tree/1.21.1) | NeoForge 1.21.1 adapter |
| [`1.20.1`](https://github.com/kuba6000/AE2-Web-Integration/tree/1.20.1) | Forge 1.20.1 adapter |
| [`1.12.2`](https://github.com/kuba6000/AE2-Web-Integration/tree/1.12.2) | Forge 1.12.2 adapter for AE2 UEL |
| [`1.7.10`](https://github.com/kuba6000/AE2-Web-Integration/tree/1.7.10) | Forge 1.7.10 adapter for GTNH |

Each version branch includes `core` as a Git submodule pinned to a tested commit and packages it into the final
mod JAR. Shared behavior should normally be changed on `core`; a version branch should only contain the adapter
needed to make that behavior work on its Minecraft and AE2 version.

### Building and checking

Use a Minecraft-version branch to build an installable mod JAR. Initialize its submodules before
building:

```shell
git submodule update --init --recursive
./gradlew build
```

The shared core can be checked independently with `./gradlew test spotlessCheck`.

Building requires Node.js 22.13+ on the 22.x line or Node.js 24+, with npm on `PATH`, alongside
the JDK required by the branch. Gradle installs the locked frontend dependencies, compiles TypeScript,
and packages the JavaScript output. Node.js and npm are not required to run the finished mod.

See [frontend development](tools/ui-tests/README.md) for frontend commands and browser tests.

### API and custom themes

The web API is available under `/api/` on your Minecraft server. See the
[API documentation](https://ae2web.kuba6000.pl/docs/); send requests to your own server, not the
documentation host.

- [OpenAPI generator](tools/openapi-doclet/README.md) — generate the API specification from endpoint
  annotations and Javadocs.
- [Theme author guide](docs/themes/README.md) — theme contracts, development tools and the example project.

## License

Project code and original interface icons are licensed under **LGPL-3.0-or-later**.
Bundled third-party assets retain their own licenses:

| Component | Author / source | License |
| --- | --- | --- |
| Project code and original interface icons | kuba6000 | [LGPL-3.0-or-later](LICENSE) |
| Charts | [uPlot, Leon Sorokin](https://github.com/leeoniya/uPlot) | MIT |
| Interface icons | [HackerNoon Pixel Icon Library](https://github.com/hackernoon/pixel-icon-library) | [MIT](src/main/resources/assets/web/themes/default/icons/hackernoon/LICENSE) |
| Lato font | [Łukasz Dziedzic (tyPoland)](https://github.com/google/fonts/tree/main/ofl/lato) | [SIL Open Font License 1.1](src/main/resources/assets/web/themes/default/fonts/lato/LICENSE) |
| Monocraft font | [Idrees Hassan](https://github.com/IdreesInc/Monocraft) | [SIL Open Font License 1.1](src/main/resources/assets/web/themes/default/fonts/monocraft/LICENSE) |
