# ModDetectorPlugin

A Paper plugin that detects and manages client-side mods through plugin message channels. Server administrators can configure which mods are allowed or blocked, with options to kick players, log detections, or both.

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

- Paper 1.21 or higher
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

# Action: "kick", "log", or "both"
action: both

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

When `track-detections: true`, all detections are logged to `plugins/ModDetectorPlugin/detections.txt`:

```
[2024-01-15 14:30:22] uuid-here | PlayerName | Xaero's World Map, Jade
```

## How It Works

ModDetectorPlugin monitors plugin message channels that clients register when connecting. Many client-side mods register channels to communicate with servers, even if the server doesn't have a corresponding plugin. By detecting these channel registrations, the plugin can identify which mods a player is using.

## Building

```bash
./gradlew build
```

The compiled JAR will be in `build/libs/`.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
