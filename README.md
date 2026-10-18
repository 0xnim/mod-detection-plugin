# ModDetectorPlugin

A Paper/Folia plugin that detects and manages client-side mods through plugin message channels. Server administrators can configure which mods are allowed or blocked, with configurable kicks, admin alerts, and session logging.

## Features

- **Mod Detection** - Automatically detects client mods that register plugin message channels
- **Flexible Filtering** - Whitelist or blacklist mode for granular control
- **Configurable Actions** - Kick players, log detections, or both
- **Known Mod Database** - Pre-configured detection patterns for popular mods
- **Custom Patterns** - Add your own channel patterns with wildcard support
- **Admin Notifications** - Real-time alerts when mods are detected
- **Detection Logging** - Track all detections to a file for review
- **History Tools** - Offline lookups, session comparisons, and paginated searches
- **Rule Preview** - Preview an added channel pattern against online players without applying it
- **Per-Mod Actions** - Log, alert, or kick for each identified mod
- **Temporary Exceptions** - Persistent player/mod exceptions with automatic expiry
- **Configuration Checks** - Warnings for unknown IDs, duplicate patterns, and conflicting definitions
- **Discovery Statistics** - First/last observation and distinct player counts per channel
- **Player Exemptions** - Exempt player UUIDs, names, or operators while retaining channel tracking
- **Folia Scheduling** - Run kicks and admin notifications on the owning player scheduler
- **MiniMessage Support** - Customizable kick messages with formatting

## Requirements

- Paper or Folia 1.21.8 (Paper API build target)
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
| `/moddetector mods [page]` | List known and custom mod definitions | `moddetector.admin` |
| `/moddetector players [page]` | List online players with detections | `moddetector.admin` |
| `/moddetector info <name-or-uuid> [page]` | Show current channels and retained history, including offline players | `moddetector.admin` |
| `/moddetector changes <name-or-uuid> [page]` | Compare current observations with the previous session, or the last two completed sessions for offline players | `moddetector.admin` |
| `/moddetector explain <online-player> [page]` | Explain channel rules, actions, bypass permission, whitelist status, and exceptions | `moddetector.admin` |
| `/moddetector preview <pattern> [page]` | Preview adding a channel pattern to the current rule list | `moddetector.admin` |
| `/moddetector validate [page]` | Show warnings from the last configuration load | `moddetector.admin` |
| `/moddetector search <mod-or-channel-or-date> <value> [page]` | Search saved player history | `moddetector.admin` |
| `/moddetector discovered [page]` | Show discovered channels and observation statistics | `moddetector.admin` |
| `/moddetector exception add <name-or-uuid> <mod-id> <minutes>` | Grant a temporary exception | `moddetector.admin` |
| `/moddetector exception remove <name-or-uuid> <mod-id>` | Remove an exception and recheck an online player | `moddetector.admin` |
| `/moddetector exception list [page]` | List active exceptions | `moddetector.admin` |
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

# Exempt UUIDs or case-insensitive names from enforcement, but retain tracking
whitelisted-players: []
whitelist-operators: false

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

When `track-detections: true`, completed sessions, including sessions without registered channels, are stored in
`plugins/ModDetectorPlugin/detections.json`. Despite its filename, this file uses JSON Lines:
one player record per line. Records include UUID, name, lifetime totals, aggregate mods and
channels, and retained sessions. Session records use full snapshots or added/removed deltas.
New sessions also contain `registeredChannels`, the complete set observed during the session.
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
The output also shows whether kicks are enabled, whether the player has bypass permission,
and whether the player is whitelisted. `whitelisted-players` accepts UUIDs or case-insensitive
names; UUIDs are recommended because names can change. `whitelist-operators` defaults to false.
All exemptions preserve channel tracking and session history, subject to logging settings.
Pending kicks recheck exemptions and the `kick` setting before execution.
Repeated detections of the same mod produce one alert per session.

For observation without kicks or admin alerts, set `kick: false` and `notify-admins: false`.
Console detection messages remain enabled.

## History and administration examples

```text
/md info OfflinePlayer 2
/md changes OfflinePlayer
/md search mod jade
/md search mod "Simple Voice Chat"
/md search channel servux:
/md search date 2026-10-17
/md preview jade:*
/md validate
/md exception add PlayerName jade 60
/md exception list
/md exception remove PlayerName jade
```

Lists contain eight results per page. Player names match online players first, then the latest
saved name. Ambiguous saved names require a UUID. Lookups do not contact an external profile
service. Exception commands also accept UUIDs without existing history.

Mod and channel searches use case-insensitive substrings. Mod searches also accept a configured
mod ID. Date searches use `YYYY-MM-DD` in UTC and match retained sessions overlapping that date.
Mod searches include lifetime aggregates; channel searches include retained complete snapshots,
unknown-channel aggregates, and discovery observations. Older records may lack known channel IDs.
Date searches and offline comparisons are limited by session retention.

`changes` reports observed mods and channels, not verified installation changes. A live session
can register more channels later. Legacy sessions have no complete channel snapshot, so channel
comparisons explicitly report unavailable data. New sessions with zero channels permit comparisons
after a player stops registering all previously observed channels.

`preview` simulates adding one pattern to `custom-patterns`. In blacklist mode, this can block
additional channels. In whitelist mode, this can allow additional channels. Results show changed
online channel decisions and whether the proposed decision would kick each player, including
per-mod actions, global switches, bypass permissions, and temporary exceptions. It does not edit
configuration or act on players. Apply a chosen pattern in `config.yml`, then use `/md reload`.

`validate` reports warnings from the loaded configuration. Reload after editing. Checks include
unknown IDs, invalid actions or mode, negative retention, empty or duplicate filter patterns,
empty custom definitions, custom IDs colliding with built-ins, and identical identification
patterns assigned to different mods. It does not prove that arbitrary wildcard patterns are disjoint.

## Actions and temporary exceptions

```yaml
mod-actions:
  jade: alert
  xaeros-minimap: log
  journeymap: kick
```

Actions apply only when the existing whitelist/blacklist rules block a channel. An unspecified
action defaults to `kick`, preserving existing behavior. `log` keeps console/session logging;
`alert` also notifies administrators; `kick` also schedules a kick. `kick: false` disables all
kicks, and `notify-admins: false` disables all alerts. Session persistence still depends on
`track-detections`. Actions follow the identified mod: a broad `servux:*` blocking rule can still
use the `litematica` action for `servux:litematics`.

Exceptions use the identified mod ID and player UUID. They suppress blocking alerts and kicks
for that pair while preserving session history and optional channel discovery. They cannot cover
unidentified channels. Durations range from 1 to 525600 minutes. Exception mod IDs accept letters,
digits, hyphens, and underscores. Changes are saved atomically to `exceptions.yml` before success
is reported. Exceptions survive restarts; expiry uses UTC time and online players are rechecked
every 20 server ticks. Removing an exception also rechecks the player. Pending kicks recheck
current rules, permissions, actions, and exceptions before execution. Invalid exception files
disable the plugin with a console error instead of silently discarding saved exceptions.

## Discovery statistics

With `log-all-channels: true`, registrations record per-player first/last observation timestamps
in `detections.json`. `/md discovered` aggregates those timestamps and distinct player counts.
The counts start when statistics are first recorded; existing discovery lists cannot recover
old timestamps or player counts. Session retention does not remove observation statistics.
The existing `discovered-channels.json` array remains compatible. Statistics use the same batched
save and retry mechanism as player history.

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

The plugin uses Paper's native entity and async schedulers, which also work on Folia.
Player quit events clean up session state and cancel pending kicks; no global cleanup task
removes state concurrently with player events. Configuration reads are serialized with reloads.
Java 21 and the existing API baseline are retained; this change does not certify Minecraft 26.2 support.
Live integration checks passed on Paper 1.21.8 build 60 and Folia 1.21.8 build 6 with Java 21.
Scripted protocol clients verified channel registration, kicks, UUID/name/operator/permission
exemptions, tracking, reloads during activity, pending-kick cancellation, disconnect/reconnect,
history across shutdown and restart, player-issued admin commands, and timed background writes.
Additional checks covered rule previews, per-mod log/alert actions, and temporary-exception removal and automatic expiry.
The Folia checks used two tick threads and confirmed
that the admin and detected player occupied different regions. Unit tests also cover these
logic paths with mocked server APIs.

## Contribution credit

Player whitelist loading and identifier normalization were adapted from
[PR #8](https://github.com/0xnim/mod-detection-plugin/pull/8) by perfectlyOrganized
(commit `0b99e87c51d1c9a3518999610bb373d80fe322ef`), under this project's GPLv3 license.
The integration preserves tracking for exempt players and uses native Paper/Folia scheduling.
The PR's Java 25/API upgrade and FoliaLib dependency were not imported.

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.
