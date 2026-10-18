package xyz.nim.modDetectorPlugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.UUID;

@SuppressWarnings("UnstableApiUsage")
public final class ModDetectorPlugin extends JavaPlugin {

    private ModFilterConfig modFilterConfig;
    private ModMessageListener messageListener;
    private DetectionLogger detectionLogger;
    private TemporaryExceptions temporaryExceptions;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask exceptionTask;

    @Override
    public void onEnable() {
        modFilterConfig = new ModFilterConfig(this);
        modFilterConfig.load();

        try {
            temporaryExceptions = new TemporaryExceptions(getDataFolder().toPath().resolve("exceptions.yml"), java.time.Clock.systemUTC());
        } catch (java.io.IOException e) {
            getLogger().severe(e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        detectionLogger = new DetectionLogger(this);
        messageListener = new ModMessageListener(this, modFilterConfig, detectionLogger);

        getServer().getPluginManager().registerEvents(messageListener, this);

        registerPluginChannels();

        registerCommands();
        exceptionTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            for (UUID uuid : temporaryExceptions.expire()) {
                var player = Bukkit.getPlayer(uuid);
                if (player != null) player.getScheduler().run(this, recheck -> messageListener.recheck(player), null);
            }
        }, 20L, 20L);

        getLogger().info("ModDetector enabled - monitoring plugin message channels");
    }

    private void registerCommands() {
        new AdminCommands(this, modFilterConfig, detectionLogger, messageListener).register();
    }

    public TemporaryExceptions getTemporaryExceptions() { return temporaryExceptions; }

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
        if (exceptionTask != null) exceptionTask.cancel();
        if (messageListener != null) messageListener.shutdown();
        if (detectionLogger != null) detectionLogger.shutdown();
        getLogger().info("ModDetector disabled");
    }

    public ModFilterConfig getModFilterConfig() {
        return modFilterConfig;
    }

}
