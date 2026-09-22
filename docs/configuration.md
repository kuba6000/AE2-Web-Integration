# Configuration

All Minecraft versions use `config/ae2webintegration/config.toml`. Options have descriptions in the file.
Edit it and run `/ae2webintegration reload`, or restart the server.

```toml
[general]
port = 2324
public_mode = true

[discord]
webhook = ''

[tracking]
track_machine_crafting = false
```

| Setting | Default | Purpose |
| --- | --- | --- |
| `general.port` | `2324` | Web server port, from 1 to 65535. |
| `general.password` | Generated | Password for the `Admin` web account. |
| `general.public_mode` | `true` | Allow individual player accounts; when false, only Admin can log in. |
| `general.allow_no_password_on_localhost` | `true` | Give loopback clients admin access without logging in. |
| `general.trusted_proxies` | `''` | Comma-separated proxy IPs or CIDRs; see the README's proxy instructions. |
| `general.max_requests_before_logged_in_per_minute` | `20` | Unauthenticated request limit per client per minute, from 1 to 1000. |
| `general.check_for_updates` | `true` | Check for mod updates. |
| `discord.webhook` | `''` | Discord webhook URL; empty disables notifications. |
| `discord.role_id` | `''` | Role to mention on errors; empty disables mentions. |
| `discord.minimum_crafting_duration_seconds` | `0` | Minimum crafting duration for a notification. |
| `discord.minimum_crafting_amount` | `0` | Minimum crafted amount for a notification. |
| `tracking.track_machine_crafting` | `false` | Include crafting requested directly by machines. |

Both Discord minimums must be met; zero disables that filter. Discord integration requires public mode
to be disabled. Missing settings receive defaults. Invalid configuration prevents reload and leaves the
previous settings active.

Single-quoted TOML strings keep backslashes literally, for example `password = 'abc\def'`.
Double-quoted strings interpret escapes such as `\n`. Comments attached to settings and categories are
retained when saving; layout and quote style may change.
