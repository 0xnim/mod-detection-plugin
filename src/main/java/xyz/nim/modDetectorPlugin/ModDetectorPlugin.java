package xyz.nim.modDetectorPlugin;

import com.google.inject.Inject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Plugin(
        id = "moddetector",
        name = "ModDetectorPlugin",
        version = "1.2.1-velocity",
        description = "Detects and filters client mods via plugin message channels",
        authors = {"nim"}
)
public class ModDetectorPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ModFilterConfig modFilterConfig;
    private ModMessageListener messageListener;
    private DetectionLogger detectionLogger;

    @Inject
    public ModDetectorPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        modFilterConfig = new ModFilterConfig(this);
        modFilterConfig.load();

        detectionLogger = new DetectionLogger(this);
        messageListener = new ModMessageListener(this, modFilterConfig, detectionLogger);

        server.getEventManager().register(this, messageListener);

        registerCommands();

        logger.info("ModDetector enabled - monitoring plugin message channels on proxy");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (detectionLogger != null) {
            detectionLogger.shutdown();
        }
        logger.info("ModDetector disabled");
    }

    private void registerCommands() {
        LiteralCommandNode<CommandSource> rootNode = LiteralArgumentBuilder.<CommandSource>literal("moddetector")
                .requires(source -> source.hasPermission("moddetector.admin"))
                .executes(ctx -> {
                    CommandSource sender = ctx.getSource();
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
                    return 1;
                })
                .then(LiteralArgumentBuilder.<CommandSource>literal("reload")
                        .executes(ctx -> {
                            modFilterConfig.load();
                            ctx.getSource().sendMessage(
                                    Component.text("[ModDetector] Configuration reloaded.", NamedTextColor.GREEN));
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<CommandSource>literal("status")
                        .executes(ctx -> {
                            CommandSource sender = ctx.getSource();
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
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<CommandSource>literal("debug")
                        .executes(ctx -> {
                            CommandSource sender = ctx.getSource();
                            sender.sendMessage(Component.text("[ModDetector] Debug mode is currently: " +
                                    (modFilterConfig.isDebug() ? "enabled" : "disabled"), NamedTextColor.YELLOW));
                            sender.sendMessage(Component.text("Edit config.yml and use /moddetector reload to change.", NamedTextColor.GRAY));
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<CommandSource>literal("mods")
                        .executes(ctx -> {
                            CommandSource sender = ctx.getSource();
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
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<CommandSource>literal("players")
                        .executes(ctx -> {
                            CommandSource sender = ctx.getSource();
                            var detected = messageListener.getDetectedChannels();
                            var allChannels = messageListener.getAllRegisteredChannels();

                            var dataToShow = modFilterConfig.isLogAllChannels() ? allChannels : detected;

                            if (dataToShow.isEmpty()) {
                                sender.sendMessage(Component.text("[ModDetector] No players with registered channels currently online.", NamedTextColor.YELLOW));
                                return 1;
                            }

                            String title = modFilterConfig.isLogAllChannels()
                                    ? "=== Players with Registered Channels ==="
                                    : "=== Players with Detected Mods ===";
                            sender.sendMessage(Component.text(title, NamedTextColor.GOLD));

                            dataToShow.forEach((uuid, channels) -> {
                                server.getPlayer(uuid).ifPresent(player -> {
                                    String playerName = player.getUsername();
                                    Component playerComponent = Component.text("  " + playerName, NamedTextColor.YELLOW)
                                            .append(Component.text(" (" + channels.size() + " channels)", NamedTextColor.GRAY))
                                            .hoverEvent(HoverEvent.showText(Component.text("Click to view channels", NamedTextColor.AQUA)))
                                            .clickEvent(ClickEvent.runCommand("/md info " + playerName));
                                    sender.sendMessage(playerComponent);
                                });
                            });
                            return 1;
                        }))
                .then(LiteralArgumentBuilder.<CommandSource>literal("info")
                        .then(RequiredArgumentBuilder.<CommandSource, String>argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    server.getAllPlayers().forEach(p -> builder.suggest(p.getUsername()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSource sender = ctx.getSource();
                                    String playerName = StringArgumentType.getString(ctx, "player");
                                    Optional<Player> targetOpt = server.getPlayer(playerName);

                                    if (targetOpt.isEmpty()) {
                                        sender.sendMessage(Component.text("[ModDetector] Player not found or not online.", NamedTextColor.RED));
                                        return 1;
                                    }

                                    Player target = targetOpt.get();
                                    UUID uuid = target.getUniqueId();
                                    sender.sendMessage(Component.text("=== Channel Info: " + target.getUsername() + " ===", NamedTextColor.GOLD));

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

                                    var historicalData = detectionLogger.getPlayerData(uuid);
                                    if (historicalData != null) {
                                        boolean hasMods = historicalData.mods != null && !historicalData.mods.isEmpty();
                                        boolean hasChannels = historicalData.channels != null && !historicalData.channels.isEmpty();

                                        if (hasMods || hasChannels) {
                                            sender.sendMessage(Component.text("Historical Data:", NamedTextColor.YELLOW));
                                            sender.sendMessage(Component.text("  Last seen: " + historicalData.lastSeen, NamedTextColor.GRAY));

                                            if (hasMods) {
                                                sender.sendMessage(Component.text("  Mods (" + historicalData.mods.size() + "):", NamedTextColor.AQUA));
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
                                        }
                                    }

                                    return 1;
                                })))
                .then(LiteralArgumentBuilder.<CommandSource>literal("discovered")
                        .executes(ctx -> {
                            CommandSource sender = ctx.getSource();
                            var discovered = detectionLogger.getDiscoveredChannels();
                            if (discovered.isEmpty()) {
                                sender.sendMessage(Component.text("[ModDetector] No channels discovered yet.", NamedTextColor.YELLOW));
                                return 1;
                            }
                            sender.sendMessage(Component.text("=== All Discovered Channels (" + discovered.size() + ") ===", NamedTextColor.GOLD));
                            for (String channel : discovered) {
                                String modName = modFilterConfig.getModName(channel);
                                boolean isBlocked = modFilterConfig.shouldBlock(channel);
                                NamedTextColor color = isBlocked ? NamedTextColor.RED : NamedTextColor.GREEN;
                                sender.sendMessage(Component.text("  " + channel, color)
                                        .append(Component.text(" -> " + modName, NamedTextColor.GRAY)));
                            }
                            return 1;
                        }))
                .build();

        BrigadierCommand command = new BrigadierCommand(rootNode);
        server.getCommandManager().register("moddetector", command, "md");
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public ModFilterConfig getModFilterConfig() {
        return modFilterConfig;
    }
}
