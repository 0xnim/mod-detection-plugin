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
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "moddetector",
        name = "ModDetectorPlugin",
        version = "1.1.0-velocity",
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
        logger.info("ModDetector disabled");
    }

    private void registerCommands() {
        LiteralCommandNode<CommandSource> rootNode = LiteralArgumentBuilder.<CommandSource>literal("moddetector")
                .requires(source -> source.hasPermission("moddetector.admin"))
                .executes(ctx -> {
                    CommandSource sender = ctx.getSource();
                    sender.sendMessage(Component.text("=== ModDetector Commands ===", NamedTextColor.GOLD));
                    sender.sendMessage(Component.text("/moddetector reload", NamedTextColor.YELLOW)
                            .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
                    sender.sendMessage(Component.text("/moddetector status", NamedTextColor.YELLOW)
                            .append(Component.text(" - Show current status", NamedTextColor.GRAY)));
                    sender.sendMessage(Component.text("/moddetector mods", NamedTextColor.YELLOW)
                            .append(Component.text(" - List known mod IDs", NamedTextColor.GRAY)));
                    sender.sendMessage(Component.text("/moddetector players", NamedTextColor.YELLOW)
                            .append(Component.text(" - Show tracked players with mods", NamedTextColor.GRAY)));
                    sender.sendMessage(Component.text("/moddetector debug", NamedTextColor.YELLOW)
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
                            sender.sendMessage(Component.text("Action: ", NamedTextColor.GRAY)
                                    .append(Component.text(modFilterConfig.getAction().name(), NamedTextColor.YELLOW)));
                            sender.sendMessage(Component.text("Debug: ", NamedTextColor.GRAY)
                                    .append(Component.text(modFilterConfig.isDebug() ? "enabled" : "disabled", NamedTextColor.YELLOW)));
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
                            if (detected.isEmpty()) {
                                sender.sendMessage(Component.text("[ModDetector] No players with detected mods currently online.", NamedTextColor.YELLOW));
                                return 1;
                            }
                            sender.sendMessage(Component.text("=== Players with Detected Mods ===", NamedTextColor.GOLD));
                            detected.forEach((uuid, mods) -> {
                                server.getPlayer(uuid).ifPresent(player -> {
                                    sender.sendMessage(Component.text("  " + player.getUsername(), NamedTextColor.YELLOW)
                                            .append(Component.text(": ", NamedTextColor.GRAY))
                                            .append(Component.text(String.join(", ", mods), NamedTextColor.RED)));
                                });
                            });
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
