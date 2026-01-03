# Changelog

All notable changes to ModDetectorPlugin will be documented in this file.

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
