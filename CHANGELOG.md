# Changelog

All notable changes to ModDetectorPlugin will be documented in this file.

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
