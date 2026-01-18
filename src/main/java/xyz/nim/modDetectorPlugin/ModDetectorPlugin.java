package xyz.nim.modDetectorPlugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public final class ModDetectorPlugin extends JavaPlugin {

    private ModFilterConfig modFilterConfig;
    private ModMessageListener messageListener;
    private DetectionLogger detectionLogger;

    @Override
    public void onEnable() {
        modFilterConfig = new ModFilterConfig(this);
        modFilterConfig.load();

        detectionLogger = new DetectionLogger(this);
        messageListener = new ModMessageListener(this, modFilterConfig, detectionLogger);

        getServer().getPluginManager().registerEvents(messageListener, this);

        registerPluginChannels();

        registerCommands();

        getLogger().info("ModDetector enabled - monitoring plugin message channels");
    }

    private void registerCommands() {
        LifecycleEventManager<Plugin> manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                    Commands.literal("moddetector")
                            .requires(source -> source.getSender().hasPermission("moddetector.admin"))
                            .then(Commands.literal("reload")
                                    .executes(ctx -> {
                                        modFilterConfig.load();
                                        ctx.getSource().getSender().sendMessage(
                                                Component.text("[ModDetector] Configuration reloaded.", NamedTextColor.GREEN));
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("status")
                                    .executes(ctx -> {
                                        var sender = ctx.getSource().getSender();
                                        sender.sendMessage(Component.text("=== ModDetector Status ===", NamedTextColor.GOLD));
                                        sender.sendMessage(Component.text("Mode: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.getMode().name(), NamedTextColor.YELLOW)));
                                        sender.sendMessage(Component.text("Kick: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.isKick() ? "enabled" : "disabled", NamedTextColor.YELLOW)));
                                        sender.sendMessage(Component.text("Debug: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.isDebug() ? "enabled" : "disabled", NamedTextColor.YELLOW)));
                                        sender.sendMessage(Component.text("Log All Channels: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.isLogAllChannels() ? "enabled" : "disabled", NamedTextColor.YELLOW)));
                                        sender.sendMessage(Component.text("Notify Admins: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.isNotifyAdmins() ? "enabled" : "disabled", NamedTextColor.YELLOW)));
                                        sender.sendMessage(Component.text("Track Detections: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.isTrackDetections() ? "enabled" : "disabled", NamedTextColor.YELLOW)));
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("debug")
                                    .executes(ctx -> {
                                        var sender = ctx.getSource().getSender();
                                        sender.sendMessage(Component.text("[ModDetector] Debug mode is currently: " +
                                                (modFilterConfig.isDebug() ? "enabled" : "disabled"), NamedTextColor.YELLOW));
                                        sender.sendMessage(Component.text("Edit config.yml and use /moddetector reload to change.", NamedTextColor.GRAY));
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("mods")
                                    .executes(ctx -> {
                                        var sender = ctx.getSource().getSender();
                                        sender.sendMessage(Component.text("=== Known Mods ===", NamedTextColor.GOLD));
                                        modFilterConfig.getKnownMods().values().forEach(mod -> {
                                            sender.sendMessage(Component.text("  " + mod.getId(), NamedTextColor.YELLOW)
                                                    .append(Component.text(" - " + mod.getName(), NamedTextColor.WHITE))
                                                    .append(Component.text(" (" + mod.getDescription() + ")", NamedTextColor.GRAY)));
                                        });
                                        var customMods = modFilterConfig.getCustomMods();
                                        if (!customMods.isEmpty()) {
                                            sender.sendMessage(Component.text("=== Custom Mods ===", NamedTextColor.GOLD));
                                            customMods.values().forEach(mod -> {
                                                sender.sendMessage(Component.text("  " + mod.getId(), NamedTextColor.AQUA)
                                                        .append(Component.text(" - " + mod.getName(), NamedTextColor.WHITE))
                                                        .append(Component.text(" (" + mod.getDescription() + ")", NamedTextColor.GRAY)));
                                            });
                                        }
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("players")
                                    .executes(ctx -> {
                                        var sender = ctx.getSource().getSender();
                                        var detected = messageListener.getDetectedChannels();
                                        var allChannels = messageListener.getAllRegisteredChannels();

                                        // Show all channels if log-all-channels is enabled, otherwise show detected
                                        var dataToShow = modFilterConfig.isLogAllChannels() ? allChannels : detected;

                                        if (dataToShow.isEmpty()) {
                                            sender.sendMessage(Component.text("[ModDetector] No players with registered channels currently online.", NamedTextColor.YELLOW));
                                            return Command.SINGLE_SUCCESS;
                                        }

                                        String title = modFilterConfig.isLogAllChannels()
                                                ? "=== Players with Registered Channels ==="
                                                : "=== Players with Detected Mods ===";
                                        sender.sendMessage(Component.text(title, NamedTextColor.GOLD));

                                        dataToShow.forEach((uuid, channels) -> {
                                            var player = getServer().getPlayer(uuid);
                                            if (player != null && player.isOnline()) {
                                                String playerName = player.getName();
                                                sender.sendMessage(Component.text("  " + playerName, NamedTextColor.YELLOW)
                                                        .clickEvent(ClickEvent.runCommand("/md info " + playerName))
                                                        .hoverEvent(HoverEvent.showText(Component.text("Click to view channels", NamedTextColor.GRAY)))
                                                        .append(Component.text(" (" + channels.size() + " channels)", NamedTextColor.GRAY)));
                                            }
                                        });
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("info")
                                    .then(Commands.argument("player", ArgumentTypes.player())
                                            .executes(ctx -> {
                                                var sender = ctx.getSource().getSender();
                                                var playerSelector = ctx.getArgument("player", io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver.class);
                                                Player target;
                                                try {
                                                    target = playerSelector.resolve(ctx.getSource()).getFirst();
                                                } catch (Exception e) {
                                                    sender.sendMessage(Component.text("[ModDetector] Player not found or not online.", NamedTextColor.RED));
                                                    return Command.SINGLE_SUCCESS;
                                                }

                                                UUID uuid = target.getUniqueId();
                                                sender.sendMessage(Component.text("=== Channel Info: " + target.getName() + " ===", NamedTextColor.GOLD));

                                                // Show current session channels
                                                Set<String> currentChannels = messageListener.getAllRegisteredChannels().get(uuid);
                                                if (currentChannels != null && !currentChannels.isEmpty()) {
                                                    sender.sendMessage(Component.text("Current Session (" + currentChannels.size() + " channels):", NamedTextColor.YELLOW));
                                                    for (String channel : currentChannels) {
                                                        String modName = modFilterConfig.getModName(channel);
                                                        boolean isBlocked = modFilterConfig.shouldBlock(channel);
                                                        NamedTextColor color = isBlocked ? NamedTextColor.RED : NamedTextColor.GREEN;
                                                        sender.sendMessage(Component.text("  " + channel, color)
                                                                .append(Component.text(" -> " + modName, NamedTextColor.GRAY)));
                                                    }
                                                } else {
                                                    sender.sendMessage(Component.text("No channels registered this session.", NamedTextColor.GRAY));
                                                }

                                                // Show historical data if available
                                                var historicalData = detectionLogger.getPlayerData(uuid);
                                                if (historicalData != null) {
                                                    boolean hasMods = historicalData.mods != null && !historicalData.mods.isEmpty();
                                                    boolean hasChannels = historicalData.channels != null && !historicalData.channels.isEmpty();
                                                    boolean hasSessions = historicalData.sessions != null && !historicalData.sessions.isEmpty();

                                                    if (hasMods || hasChannels || hasSessions) {
                                                        sender.sendMessage(Component.text("Historical Data:", NamedTextColor.YELLOW));
                                                        sender.sendMessage(Component.text("  First seen: " + historicalData.firstSeen, NamedTextColor.GRAY));
                                                        sender.sendMessage(Component.text("  Total playtime: " + formatDuration(historicalData.totalTimePlayedSeconds), NamedTextColor.GRAY));

                                                        if (hasMods) {
                                                            sender.sendMessage(Component.text("  All mods ever used (" + historicalData.mods.size() + "):", NamedTextColor.AQUA));
                                                            for (String mod : historicalData.mods) {
                                                                sender.sendMessage(Component.text("    " + mod, NamedTextColor.WHITE));
                                                            }
                                                        }

                                                        if (hasChannels) {
                                                            sender.sendMessage(Component.text("  Unknown Channels (" + historicalData.channels.size() + "):", NamedTextColor.GRAY));
                                                            for (String channel : historicalData.channels) {
                                                                sender.sendMessage(Component.text("    " + channel, NamedTextColor.DARK_GRAY));
                                                            }
                                                        }

                                                        if (hasSessions) {
                                                            sender.sendMessage(Component.text("  Sessions (" + historicalData.sessionCount + "):", NamedTextColor.YELLOW));
                                                            for (int i = 0; i < historicalData.sessions.size(); i++) {
                                                                var session = historicalData.sessions.get(i);
                                                                String duration = formatDuration(session.durationSeconds);
                                                                // Reconstruct full mod list for this session
                                                                var sessionMods = DetectionLogger.getModsForSession(historicalData.sessions, i);
                                                                String modsStr = !sessionMods.isEmpty()
                                                                        ? String.join(", ", sessionMods)
                                                                        : "none";
                                                                sender.sendMessage(Component.text("    " + session.joinTime + " (" + duration + ")", NamedTextColor.WHITE)
                                                                        .append(Component.text(" - " + modsStr, NamedTextColor.GRAY)));
                                                            }
                                                        }
                                                    }
                                                }

                                                return Command.SINGLE_SUCCESS;
                                            })))
                            .then(Commands.literal("discovered")
                                    .executes(ctx -> {
                                        var sender = ctx.getSource().getSender();
                                        var discovered = detectionLogger.getDiscoveredChannels();
                                        if (discovered.isEmpty()) {
                                            sender.sendMessage(Component.text("[ModDetector] No channels discovered yet.", NamedTextColor.YELLOW));
                                            return Command.SINGLE_SUCCESS;
                                        }
                                        sender.sendMessage(Component.text("=== All Discovered Channels (" + discovered.size() + ") ===", NamedTextColor.GOLD));
                                        for (String channel : discovered) {
                                            String modName = modFilterConfig.getModName(channel);
                                            boolean isBlocked = modFilterConfig.shouldBlock(channel);
                                            NamedTextColor color = isBlocked ? NamedTextColor.RED : NamedTextColor.GREEN;
                                            sender.sendMessage(Component.text("  " + channel, color)
                                                    .append(Component.text(" -> " + modName, NamedTextColor.GRAY)));
                                        }
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .executes(ctx -> {
                                var sender = ctx.getSource().getSender();
                                sender.sendMessage(Component.text("=== ModDetector Commands ===", NamedTextColor.GOLD));
                                sender.sendMessage(Component.text("/md reload", NamedTextColor.YELLOW)
                                        .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/md status", NamedTextColor.YELLOW)
                                        .append(Component.text(" - Show current status", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/md mods", NamedTextColor.YELLOW)
                                        .append(Component.text(" - List known mod IDs", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/md players", NamedTextColor.YELLOW)
                                        .append(Component.text(" - Show players with registered channels", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/md info <player>", NamedTextColor.YELLOW)
                                        .append(Component.text(" - Show all channels for a player", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/md discovered", NamedTextColor.YELLOW)
                                        .append(Component.text(" - List all discovered channels", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/md debug", NamedTextColor.YELLOW)
                                        .append(Component.text(" - Show debug status", NamedTextColor.GRAY)));
                                return Command.SINGLE_SUCCESS;
                            })
                            .build(),
                    "ModDetector admin commands",
                    java.util.List.of("md")
            );
        });
    }

    private void registerPluginChannels() {
        String[] commonChannels = {
                "xaeroworldmap:main",
                "xaerominimap:main",
                "jade:server_data",
                "wthit:wthit",
                "litematica:main",
                "tweakeroo:carpet",
                "minihud:structures",
                "itemscroller:itemscroller",
                "worldedit:cui",
                "fabric:registry/sync",
                "forge:handshake",
                "fml:handshake"
        };

        for (String channel : commonChannels) {
            try {
                getServer().getMessenger().registerIncomingPluginChannel(this, channel, messageListener);
            } catch (Exception e) {
                if (modFilterConfig.isDebug()) {
                    getLogger().warning("Could not register channel: " + channel + " - " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        detectionLogger.shutdown();
        getLogger().info("ModDetector disabled");
    }

    public ModFilterConfig getModFilterConfig() {
        return modFilterConfig;
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            long mins = seconds / 60;
            long secs = seconds % 60;
            return secs > 0 ? mins + "m " + secs + "s" : mins + "m";
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return mins > 0 ? hours + "h " + mins + "m" : hours + "h";
        }
    }
}
