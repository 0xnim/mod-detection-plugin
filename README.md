# ModDetectorPlugin

A Paper plugin that detects and manages client-side mods through plugin message channels. Server administrators can configure which mods are allowed or blocked, with configurable kicks, admin alerts, and session logging.

## Features

- **Mod Detection** - Automatically detects client mods that register plugin message channels
- **Flexible Filtering** - Whitelist or blacklist mode for granular control
- **Configurable Actions** - Kick players, log detections, or both
- **Known Mod Database** - Pre-configured detection patterns for popular mods
- **Custom Patterns** - Add your own channel patterns with wildcard support
- **Admin Notifications** - Real-time alerts when mods are detected
- **Detection Logging** - Track all detections to a file for review
- **MiniMessage Support** - Customizable kick messages with formatting

## Requirements

- Paper 1.21.8 (build and test target)
- Java 21 or higher

## Installation

1. Download the latest release JAR
2. Place it in your server's `plugins/` folder
3. Restart your server
4. Configure the plugin in `plugins/ModDetectorPlugin/config.yml`

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/moddetector` | Show help | `moddetector.admin` |
| `/moddetector reload` | Reload configuration | `moddetector.admin` |
| `/moddetector status` | Show current status | `moddetector.admin` |
| `/moddetector mods` | List known mod definitions | `moddetector.admin` |
| `/moddetector players` | List online players with detections | `moddetector.admin` |
| `/moddetector info <player>` | Show current channels and retained history | `moddetector.admin` |
| `/moddetector explain <player>` | Explain current channel rules, kick setting, and bypass permission | `moddetector.admin` |
| `/moddetector discovered` | List channels recorded with log-all-channels | `moddetector.admin` |
| `/moddetector debug` | Show debug status | `moddetector.admin` |

**Alias:** `/md`

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `moddetector.admin` | Access to admin commands | OP |
| `moddetector.notify` | Receive detection notifications | OP |
| `moddetector.bypass` | Bypass mod detection (won't be kicked) | false |

## Configuration

### config.yml

```yaml
# Mode: "whitelist" or "blacklist"
# - whitelist: Only allow listed mods/patterns, block everything else
# - blacklist: Block listed mods/patterns, allow everything else
mode: blacklist

# Enable kicks for blocked channels
kick: true

# Kick message (MiniMessage format)
# Use <mods> placeholder to show detected mods
kick-message: "<red>You have been kicked for using disallowed client mods:</red><newline><yellow><mods></yellow>"

# Block mods by their ID (defined in mods.yml)
blocked-mods:
  - xaeros-worldmap
  - xaeros-minimap
  - jade
  - journeymap

# Additional custom channel patterns (use * as wildcard)
custom-patterns: []

# Enable debug logging
debug: false

# Notify admins when mods are detected
notify-admins: true

# Track detections to file
track-detections: true

# Record every registered channel, including unknown channels
log-all-channels: false

# Retain recent sessions per player; 0 disables the limit
history:
  max-sessions-per-player: 100
```

### Available Mod IDs

The following mod IDs are pre-configured in `mods.yml`:

| ID | Mod Name | Description |
|----|----------|-------------|
| `xaeros-worldmap` | Xaero's World Map | Full world map mod |
| `xaeros-minimap` | Xaero's Minimap | Minimap mod |
| `jade` | Jade | Block/entity information overlay |
| `journeymap` | JourneyMap | Real-time mapping mod |
| `litematica` | Litematica | Schematic mod (via Servux) |
| `minihud` | MiniHUD | HUD overlay mod (via Servux) |
| `servux` | Servux | Server-side data provider |
| `simple-voice-chat` | Simple Voice Chat | Voice chat mod |
| `noxesium` | Noxesium | Performance/feature mod |
| `flashback` | Flashback | Replay recording mod |
| `appleskin` | AppleSkin | Food/hunger HUD additions |

## Detection Logging

When `track-detections: true`, completed sessions with registered channels are stored in
`plugins/ModDetectorPlugin/detections.json`. Despite its filename, this file uses JSON Lines:
one player record per line. Records include UUID, name, lifetime totals, aggregate mods and
channels, and retained sessions. Session records use full snapshots or added/removed deltas.
An empty full snapshot means no known mods; missing snapshot and delta fields mean unchanged.

Writes are batched every 30 seconds and flushed on shutdown. Failed writes are retried.
An unexpected process exit can lose changes since the last successful write.
With `log-all-channels: true`, registrations also update player records and
`discovered-channels.json`, a JSON array of all discovered channels.

`history.max-sessions-per-player` defaults to 100. Retention applies on startup, reload,
and session completion. The first retained session becomes a full snapshot. Lifetime totals,
aggregate mod/channel sets, and discovered channels remain unchanged. This limit does not
remove player records or limit the number of players. Set it to 0 for unlimited sessions.
Back up existing data before lowering the limit; removed sessions cannot be restored automatically.
Old history already corrupted by missing empty snapshots cannot always be recovered.

## Rule matching

In blacklist mode, matching configured rules block a channel. In whitelist mode, those same
rules allow a channel and unmatched channels are blocked. The `blocked-mods` setting is used
in both modes despite its name. `custom-patterns` accepts `*` for any sequence and `?` for one
character. Matching ignores case. Custom mod definitions belong in `config.yml` under
`custom-mods`; the built-in `mods.yml` is loaded from the plugin JAR.

Mod identification prefers exact channels, then wildcard patterns with more literal characters.
Ties use alphabetical pattern and mod ID order. Identification is separate from enforcement:
`servux:litematics` is named Litematica, but a configured `servux:*` rule still matches it.
Use `/md explain <player>` to inspect each registered channel and its matching policy rule.
The output also shows whether kicks are enabled and whether the player has bypass permission.
Repeated detections of the same mod produce one alert per session.

For observation without kicks or admin alerts, set `kick: false` and `notify-admins: false`.
Console detection messages remain enabled.

## How It Works

ModDetectorPlugin monitors plugin message channels that clients register when connecting. Many client-side mods register channels to communicate with servers, even if the server doesn't have a corresponding plugin. These registrations provide evidence of matching mod channels. They do not prove which mods are installed.
Mods without registered channels are invisible to this plugin. Clients can omit or imitate channels,
and shared libraries can make identification ambiguous. Do not treat this plugin as comprehensive anti-cheat protection.

## Building

```bash
./gradlew build
```

Use Java 21 to run the included Gradle wrapper. The compiled JAR will be in `build/libs/`.
Run `./gradlew test` for regression tests. Test JVMs run with AWT headless mode enabled.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
