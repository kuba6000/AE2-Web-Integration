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

## Repository architecture

The [`core`](https://github.com/kuba6000/AE2-Web-Integration/tree/core) branch is the brain of the mod. It
contains almost all version-independent logic: the HTTP server, web API, authentication, configuration
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

If you only want to use the mod, download a version-specific JAR from
[Releases](https://github.com/kuba6000/AE2-Web-Integration/releases),
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/ae2-web-integration), or
[Modrinth](https://modrinth.com/mod/ae2-web-integration). Do not try to install the `core` branch by itself.

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

- Browse, sort, and filter the contents of AE2 networks
- Monitor as many networks as you want
- Public mode for servers with multiple independent players
- Submit new crafting requests, with automatic or manual CPU selection
- View the status of every crafting CPU
- Cancel crafting jobs
- Track active and completed crafting jobs
- Send crafting updates through a Discord webhook

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

Player passwords are stored as salted PBKDF2-HMAC-SHA1 hashes. A normal session is valid for one hour, or seven
days when **Remember me** is selected.

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

```text
trusted_proxies = "192.168.1.10, 10.20.0.0/24, 2001:db8::10"
```

The same option is available in the Forge `.cfg` file on older versions.

Hostnames are not accepted; use a literal address or CIDR range instead.

`X-Forwarded-For` takes priority over `X-Real-IP`. For a chain such as `client, proxy1, proxy2`, the mod walks
from right to left, skips configured trusted proxies, and uses the first untrusted address as the client. This
prevents a client from granting itself localhost access by sending a forged
`X-Forwarded-For: 127.0.0.1` header directly.

Example Nginx configuration for a proxy running on the same machine:

```nginx
location / {
    proxy_pass http://127.0.0.1:2324;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

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
4. Open `config/ae2webintegration/ae2webintegration.toml`, or
   `config/ae2webintegration/ae2webintegration.cfg` on older Minecraft versions. Configure the port, admin
   password, public mode, and other settings as needed.
5. **Disable public mode if you are playing alone.**
6. Reload the config with `/ae2webintegration reload`, or restart the server. On 1.21.1, NeoForge normally
   notices file changes automatically, but the command can still force a full config and web server reload.
7. Allow the configured port through your firewall and router as needed, or route access through a reverse
   proxy.
8. Visit `http://your-server-ip-or-domain:configured-port/` and log in with the `Admin` account and the password
   from the config.

In public mode, a player can create an account from the login page. After choosing a password, the page displays
an `/ae2webintegration auth <token>` command. The player must run that command in game to finish registration.

## Discord integration

**Discord integration only works when public mode is disabled.**

Create a Discord webhook and set its URL as `discord_webhook` in the AE2 Web Integration config. You can also
set `discord_role_id` if a role should be pinged on errors.

<img width="467" height="224" alt="AE2 Web Integration Discord message" src="https://github.com/user-attachments/assets/f9f7635d-676c-40a3-8334-f7fa35e5867a" />

## Custom website

If you already have a web server and want to host the panel there, you can! There is no complete API
documentation yet, but the [`example_website`](./example_website) directory contains a ready-to-use simple PHP
proxy. It forwards API calls from your web server to the AE2 Web Integration endpoint.

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
