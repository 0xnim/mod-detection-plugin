# Changelog

All notable changes to ModDetectorPlugin will be documented in this file.

## [1.2.3] - 2026-01-18

### Added

- **Per-Session Mod Tracking**
  - Each session now records which mods the player was using
  - `/md info` displays the full mod list for each session
  - Useful for tracking mod usage changes over time

- **Delta Compression for Sessions**
  - Sessions use smart delta compression to minimize storage
  - First session stores full mod list
  - Subsequent sessions store only changes (`added`/`removed`) if < 3 mods changed
  - Large changes (3+ mods) trigger a new full snapshot
  - Unchanged sessions store only time data

- **Enhanced `/md info` Display**
  - Shows `firstSeen` timestamp
  - Shows total playtime formatted (e.g., "1h 30m")
  - Sessions display reconstructed mod list with duration

### Changed

- **Storage Efficiency**
  - Typical player with same mods across 100 sessions: ~3KB (was ~25KB)
  - ~88% reduction in storage for players with stable mod configurations
  - Backwards compatible with existing data (old sessions show as-is)

### Example Detection Entry

```json
{
  "uuid": "f999e944-a15d-4287-bff4-34f63a97832e",
  "username": "PlayerName",
  "mods": ["Simple Voice Chat", "Jade", "Minimap"],
  "channels": ["unknown:channel"],
  "firstSeen": "2026-01-16T17:28:01Z",
  "lastSeen": "2026-01-18T12:00:00Z",
  "totalTimePlayedSeconds": 3600,
  "sessionCount": 4,
  "sessions": [
    {"joinTime": "2026-01-16T17:27:59Z", "leaveTime": "2026-01-16T17:30:00Z", "durationSeconds": 121, "mods": ["Jade", "Voice Chat"]},
    {"joinTime": "2026-01-17T10:00:00Z", "leaveTime": "2026-01-17T10:30:00Z", "durationSeconds": 1800, "added": ["Minimap"]},
    {"joinTime": "2026-01-17T14:00:00Z", "leaveTime": "2026-01-17T14:20:00Z", "durationSeconds": 1200},
    {"joinTime": "2026-01-18T12:00:00Z", "leaveTime": "2026-01-18T12:08:00Z", "durationSeconds": 479, "removed": ["Jade"]}
  ]
}
```

## [1.2.2] - 2026-01-16

### Added

- **50+ New Mod Definitions**
  - Popular mods: Roughly Enough Items (REI), Distant Horizons, Axiom, Waystones, WorldEdit, Do a Barrel Roll
  - Voice chat: Plasmo Voice, Replay Voice Chat, PV Addon Flashback
  - Controller support: Controlify, MidnightControls
  - Recording: ReplayMod, Flashback
  - Libraries: GeckoLib, Balm, Puzzles Lib, owo-lib, CreativeCore, XaeroLib, Fzzy Config, LibGui
  - Visual mods: Shulker Box Tooltip, Pick Up Notifier, Better Statistics Screen, Subtle Effects
  - Utility: Crafting Tweaks, Client Sort, Inventory Essentials, Horse Expert, PatPat
  - Player customization: Custom Player Models, Skin Shuffle, Wildfire's Female Gender Mod
  - Third-party clients: Lunar Client, Badlion Client, Feather Client, Essential, NoRisk Client
  - And many more community-contributed channel mappings

## [1.2.1] - 2026-01-16

### Added

- **Session Tracking**
  - Tracks `totalTimePlayedSeconds` across all sessions
  - Records `sessionCount` for each player
  - Maintains `sessions` array with join/leave times and duration for each session

- **Clickable Player Names**
  - Player names in `/md players` are now clickable
  - Click to run `/md info <player>` automatically
  - Hover text shows "Click to view channels"

### Changed

- **Simplified Action Config**
  - Replaced `action: kick/log/both` with simple `kick: true/false`
  - Detections are now ALWAYS logged regardless of kick setting
  - Cleaner config, same functionality

- **Smarter Detection Logging**
  - Only creates new JSON entry when player's modlist changes
  - Returning players with same mods just add session info to existing entry
  - Reduces file size and improves readability

- **Separated Mods and Channels**
  - `mods` field now contains resolved mod names (e.g., "Simple Voice Chat")
  - `channels` field now only contains unrecognized/unknown channels
  - `/md info` command updated to show both sections separately

- **Internal Improvements**
  - Replaced manual JSON parsing with Gson library
  - Atomic file writes (write to .tmp, then move) prevents corruption
  - Batched I/O with 30-second flush interval reduces disk writes
  - Memory cleanup task removes stale player data every 5 minutes
  - Proper shutdown handling ensures pending writes are flushed

### Fixed

- Console no longer shows `TextComponentImpl{...}` blobs for admin notifications

### Example Detection Entry

```json
{
  "uuid": "f999e944-a15d-4287-bff4-34f63a97832e",
  "username": "PlayerName",
  "mods": ["Simple Voice Chat", "Noxesium", "AppleSkin", "Fabric API"],
  "channels": ["civ:handshake", "civ:class_xp"],
  "firstSeen": "2026-01-16T17:28:01Z",
  "lastSeen": "2026-01-16T17:32:04Z",
  "totalTimePlayedSeconds": 243,
  "sessionCount": 2,
  "sessions": [
    {"joinTime": "2026-01-16T17:27:59Z", "leaveTime": "2026-01-16T17:28:01Z", "durationSeconds": 2},
    {"joinTime": "2026-01-16T17:29:20Z", "leaveTime": "2026-01-16T17:32:04Z", "durationSeconds": 241}
  ]
}
```

## [1.2.0] - 2026-01-16

### Added

- **Log All Channels Mode**
  - New `log-all-channels` config option to log ALL channel registrations to file
  - Tracks every mod channel a player registers, not just blocked ones
  - No console logging for better performance

- **`/md info <player>` Command**
  - View all registered channels for a specific player
  - Shows current session channels and historical data
  - Color-coded output: red = blocked, green = allowed
  - Displays resolved mod names alongside raw channel strings

- **`/md discovered` Command**
  - Lists all unique channels ever discovered on the server
  - Useful for documentation and discovering new mods
  - Persists across server restarts

- **Discovered Channels File**
  - New `discovered-channels.json` file tracks all unique channels ever seen
  - JSON array format for easy parsing
  - Automatically updated when new channels are registered

- **Smart Mod Name Resolution**
  - Channel-to-mod resolution now works for ALL mods in `mods.yml`, not just blocked ones
  - Detection logs show pretty mod names (e.g., "Simple Voice Chat" instead of "voicechat:state")
  - Unknown channels remain as raw strings for identification

### Changed

- **Consolidated Player Entries**
  - `detections.json` now uses one entry per player (updated in place)
  - Multiple channels from the same mod are grouped together
  - Format changed from `"channels"` to `"mods"` array with resolved names

- **`/md players` Command**
  - Now shows all registered channels when `log-all-channels` is enabled
  - Displays channel count per player
  - Added hint to use `/md info <player>` for details

- **`/md status` Command**
  - Now shows `Log All Channels` setting status

- **Help Command**
  - Updated to show all new commands
  - Commands now displayed with `/md` shorthand

### Example Detection Entry

```json
{"uuid":"f999e944-a15d-4287-bff4-34f63a97832e","username":"PlayerName","mods":["AppleSkin","Simple Voice Chat","Noxesium","Flashback","civ:class_xp"],"lastSeen":"2026-01-16T12:00:00Z"}
```

### Example Discovered Channels

```json
[
  "xaerominimap:main",
  "voicechat:state",
  "noxesium-v2:server_info",
  "fabric:registry/sync"
]
```

## [1.1.1] - 2026-01-12

### Changed

- **JSON Detection Logging**
  - Detection logs now output in JSON format instead of plain text
  - Log file changed from `detections.txt` to `detections.json`
  - Each line is a valid JSON object for easy parsing

- **Player Session Tracking**
  - Logs now include player UUID
  - Session join time (`sessionJoinTime`) recorded when player connects
  - Session leave time (`sessionLeaveTime`) recorded on disconnect/kick
  - Session duration in seconds (`sessionDurationSeconds`) calculated automatically

### Example Log Entry

```json
{"timestamp":"2026-01-12T14:30:45Z","uuid":"f999e944-a15d-4287-bff4-34f63a97832e","username":"PlayerName","mods":["Jade","XaerosMinimap"],"sessionJoinTime":"2026-01-12T14:25:00Z","sessionLeaveTime":"2026-01-12T14:30:45Z","sessionDurationSeconds":345}
```

## [1.1.0] - 2026-01-03

### Added

- **Silent Listener Mode**
  - Set `action: log` and `notify-admins: false` to track players without kicking
  - Detections logged immediately to file (not just on player quit)
  - Use `/moddetector players` to view online players with detected mods

- **`/moddetector players` Command**
  - Shows all online players with their detected mods
  - Useful for monitoring without taking action

- **Custom Mod Definitions**
  - New `custom-mods` config section with full mod definition support
  - Define ID, name, description, and multiple channel patterns
  - Custom mods shown in `/moddetector mods` command
  - Same features as built-in mod definitions

### Changed

- Immediate file logging in LOG mode (previously only logged on player quit)
- Updated config.yml with custom-mods documentation and examples

## [1.0.0] - 2026-01-03

### Initial Release

ModDetectorPlugin is a Paper plugin for detecting and managing client-side mods through plugin message channels.

### Features

- **Mod Detection System**
  - Detects client mods via plugin message channel registration
  - Monitors common mod channels (Xaero's maps, Jade, JourneyMap, etc.)
  - Real-time detection when players join

- **Flexible Filtering Modes**
  - Blacklist mode: Block specific mods, allow everything else
  - Whitelist mode: Allow only specific mods, block everything else

- **Configurable Actions**
  - Kick players using blocked mods
  - Log detections without kicking
  - Both kick and log simultaneously

- **Known Mod Database**
  - Pre-configured detection patterns for 15+ popular mods
  - Easy to extend with new mod definitions
  - Wildcard pattern support

- **Admin Features**
  - `/moddetector` command with reload, status, mods, and debug subcommands
  - Real-time notifications for admins when mods are detected
  - Detection logging to file for audit purposes

- **Permissions System**
  - `moddetector.admin` - Admin command access
  - `moddetector.notify` - Receive detection notifications
  - `moddetector.bypass` - Bypass mod detection

- **Customization**
  - MiniMessage formatted kick messages
  - Custom channel patterns via config
  - Configurable log format

### Supported Minecraft Versions

- Paper 1.21+
- Java 21 required
