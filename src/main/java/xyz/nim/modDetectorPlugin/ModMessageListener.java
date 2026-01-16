package xyz.nim.modDetectorPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ModMessageListener implements Listener, PluginMessageListener {

    private final ModDetectorPlugin plugin;
    private final ModFilterConfig config;
    private final DetectionLogger detectionLogger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, Set<String>> detectedChannels = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> allRegisteredChannels = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> sessionStartTimes = new ConcurrentHashMap<>();
    private final Set<UUID> pendingKicks = ConcurrentHashMap.newKeySet();

    public ModMessageListener(ModDetectorPlugin plugin, ModFilterConfig config, DetectionLogger detectionLogger) {
        this.plugin = plugin;
        this.config = config;
        this.detectionLogger = detectionLogger;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessionStartTimes.put(player.getUniqueId(), Instant.now());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChannelRegister(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        String channel = event.getChannel();

        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Player " + player.getName() + " registered channel: " + channel);
        }

        // Always track all channels in memory for /md info command
        UUID uuid = player.getUniqueId();
        Set<String> channels = allRegisteredChannels.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (channels.add(channel)) {
            // New channel registered - log to file if log-all-channels is enabled
            if (config.isLogAllChannels()) {
                detectionLogger.logChannelRegistration(player, channel, sessionStartTimes.get(uuid));
            }
        }

        if (player.hasPermission("moddetector.bypass")) {
            return;
        }

        if (config.shouldBlock(channel)) {
            handleBlockedChannel(player, channel);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Received plugin message from " + player.getName() + " on channel: " + channel);
        }

        if (player.hasPermission("moddetector.bypass")) {
            return;
        }

        if (config.shouldBlock(channel)) {
            handleBlockedChannel(player, channel);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Instant joinTime = sessionStartTimes.remove(uuid);
        Instant leaveTime = Instant.now();

        Set<String> channels = detectedChannels.remove(uuid);
        if (channels != null && !channels.isEmpty() && config.getAction() == ModFilterConfig.Action.LOG) {
            detectionLogger.logDetection(player, channels, joinTime, leaveTime);
        }

        // Clean up all registered channels tracking
        allRegisteredChannels.remove(uuid);

        pendingKicks.remove(uuid);
    }

    private void handleBlockedChannel(Player player, String channel) {
        UUID uuid = player.getUniqueId();
        ModFilterConfig.Action action = config.getAction();

        String modName = config.getModName(channel);
        Set<String> playerMods = detectedChannels.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        boolean isNewDetection = playerMods.add(modName);

        if (action == ModFilterConfig.Action.LOG || action == ModFilterConfig.Action.BOTH) {
            String logMessage = config.formatLogMessage(player.getName(), channel);
            plugin.getLogger().warning(logMessage);
        }

        if (config.isNotifyAdmins()) {
            notifyAdmins(player, modName, channel);
        }

        if (action == ModFilterConfig.Action.KICK || action == ModFilterConfig.Action.BOTH) {
            if (pendingKicks.add(uuid)) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> executeKick(player), 20L);
            }
        } else if (action == ModFilterConfig.Action.LOG && isNewDetection) {
            // In LOG-only mode, write to file immediately for each new detection
            Instant joinTime = sessionStartTimes.get(uuid);
            detectionLogger.logDetection(player, Set.of(modName), joinTime, Instant.now());
        }
    }

    private void executeKick(Player player) {
        UUID uuid = player.getUniqueId();
        pendingKicks.remove(uuid);

        if (!player.isOnline()) {
            detectedChannels.remove(uuid);
            sessionStartTimes.remove(uuid);
            return;
        }

        Set<String> channels = detectedChannels.remove(uuid);
        if (channels == null || channels.isEmpty()) {
            return;
        }

        Instant joinTime = sessionStartTimes.remove(uuid);
        Instant kickTime = Instant.now();
        detectionLogger.logDetection(player, channels, joinTime, kickTime);

        String modList = String.join(", ", channels);
        Component kickComponent = miniMessage.deserialize(
                config.getKickMessageFormat(),
                Placeholder.unparsed("mods", modList)
        );

        player.kick(kickComponent);
    }

    public Map<UUID, Set<String>> getDetectedChannels() {
        return detectedChannels;
    }

    public Map<UUID, Set<String>> getAllRegisteredChannels() {
        return allRegisteredChannels;
    }

    private void notifyAdmins(Player offender, String modName, String channel) {
        Component message = Component.text("[ModDetector] ", NamedTextColor.RED)
                .append(Component.text(offender.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" detected using: ", NamedTextColor.GRAY))
                .append(Component.text(modName, NamedTextColor.GOLD))
                .append(Component.text(" (" + channel + ")", NamedTextColor.DARK_GRAY));

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("moddetector.notify")) {
                admin.sendMessage(message);
            }
        }

        Bukkit.getConsoleSender().sendMessage(message);
    }
}
