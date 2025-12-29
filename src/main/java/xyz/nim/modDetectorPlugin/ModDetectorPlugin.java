package xyz.nim.modDetectorPlugin;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

@SuppressWarnings("UnstableApiUsage")
public final class ModDetectorPlugin extends JavaPlugin {

    private ModFilterConfig modFilterConfig;
    private ModMessageListener messageListener;

    @Override
    public void onEnable() {
        modFilterConfig = new ModFilterConfig(this);
        modFilterConfig.load();

        messageListener = new ModMessageListener(this, modFilterConfig);

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
                                        sender.sendMessage(Component.text("Action: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.getAction().name(), NamedTextColor.YELLOW)));
                                        sender.sendMessage(Component.text("Debug: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.isDebug() ? "enabled" : "disabled", NamedTextColor.YELLOW)));
                                        sender.sendMessage(Component.text("Notify Admins: ", NamedTextColor.GRAY)
                                                .append(Component.text(modFilterConfig.isNotifyAdmins() ? "enabled" : "disabled", NamedTextColor.YELLOW)));
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
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .executes(ctx -> {
                                var sender = ctx.getSource().getSender();
                                sender.sendMessage(Component.text("=== ModDetector Commands ===", NamedTextColor.GOLD));
                                sender.sendMessage(Component.text("/moddetector reload", NamedTextColor.YELLOW)
                                        .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/moddetector status", NamedTextColor.YELLOW)
                                        .append(Component.text(" - Show current status", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/moddetector mods", NamedTextColor.YELLOW)
                                        .append(Component.text(" - List known mod IDs", NamedTextColor.GRAY)));
                                sender.sendMessage(Component.text("/moddetector debug", NamedTextColor.YELLOW)
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
        getLogger().info("ModDetector disabled");
    }

    public ModFilterConfig getModFilterConfig() {
        return modFilterConfig;
    }
}
