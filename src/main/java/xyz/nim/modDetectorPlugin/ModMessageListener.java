package xyz.nim.modDetectorPlugin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ModMessageListener {

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
        startCleanupTask();
    }

    private void startCleanupTask() {
        // Clean up stale entries every 5 minutes
        plugin.getServer().getScheduler()
                .buildTask(plugin, this::cleanupStaleEntries)
                .repeat(5, TimeUnit.MINUTES)
                .schedule();
    }

    private void cleanupStaleEntries() {
        Instant cutoff = Instant.now().minusSeconds(600); // 10 minutes

        // Clean up entries for players who are no longer online
        Set<UUID> onlineUuids = ConcurrentHashMap.newKeySet();
        for (Player player : plugin.getServer().getAllPlayers()) {
            onlineUuids.add(player.getUniqueId());
        }

        // Remove tracking data for offline players
        sessionStartTimes.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        detectedChannels.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        allRegisteredChannels.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        pendingKicks.removeIf(uuid -> !onlineUuids.contains(uuid));

        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Cleaned up stale tracking entries");
        }
    }

    @Subscribe
    public void onPlayerLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        sessionStartTimes.put(player.getUniqueId(), Instant.now());
    }

    @Subscribe
    public void onChannelRegister(PlayerChannelRegisterEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        for (ChannelIdentifier channel : event.getChannels()) {
            String channelId = channel.getId();

            if (config.isDebug()) {
                plugin.getLogger().info("[DEBUG] Player " + player.getUsername() + " registered channel: " + channelId);
            }

            // Always track all channels in memory for /md info command
            Set<String> channels = allRegisteredChannels.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
            if (channels.add(channelId)) {
                // New channel registered - log to file if log-all-channels is enabled
                if (config.isLogAllChannels()) {
                    detectionLogger.logChannelRegistration(player, channelId, sessionStartTimes.get(uuid));
                }
            }

            if (player.hasPermission("moddetector.bypass")) {
                continue;
            }

            if (config.shouldBlock(channelId)) {
                handleBlockedChannel(player, channelId);
            }
        }
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Instant joinTime = sessionStartTimes.remove(uuid);
        Instant leaveTime = Instant.now();

        // Always log detections on disconnect (if there were any and not kicking)
        Set<String> channels = detectedChannels.remove(uuid);
        if (channels != null && !channels.isEmpty() && !config.isKick()) {
            detectionLogger.logDetection(player, channels, joinTime, leaveTime);
        }

        // Clean up all registered channels tracking
        allRegisteredChannels.remove(uuid);
        pendingKicks.remove(uuid);
    }

    private void handleBlockedChannel(Player player, String channel) {
        UUID uuid = player.getUniqueId();

        String modName = config.getModName(channel);
        Set<String> playerMods = detectedChannels.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        boolean isNewDetection = playerMods.add(modName);

        // Always log to console
        String logMessage = config.formatLogMessage(player.getUsername(), channel);
        plugin.getLogger().warn(logMessage);

        if (config.isNotifyAdmins()) {
            notifyAdmins(player, modName, channel);
        }

        if (config.isKick()) {
            // Schedule kick with batching
            if (pendingKicks.add(uuid)) {
                plugin.getServer().getScheduler()
                        .buildTask(plugin, () -> executeKick(player))
                        .delay(1, TimeUnit.SECONDS)
                        .schedule();
            }
        } else if (isNewDetection) {
            // Not kicking - log detection immediately for each new mod
            Instant joinTime = sessionStartTimes.get(uuid);
            detectionLogger.logDetection(player, Set.of(modName), joinTime, Instant.now());
        }
    }

    private void executeKick(Player player) {
        UUID uuid = player.getUniqueId();
        pendingKicks.remove(uuid);

        if (!player.isActive()) {
            detectedChannels.remove(uuid);
            sessionStartTimes.remove(uuid);
            allRegisteredChannels.remove(uuid);
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

        // This disconnects from the entire proxy - no redirect to lobby!
        player.disconnect(kickComponent);
    }

    public Map<UUID, Set<String>> getDetectedChannels() {
        return detectedChannels;
    }

    public Map<UUID, Set<String>> getAllRegisteredChannels() {
        return allRegisteredChannels;
    }

    private void notifyAdmins(Player offender, String modName, String channel) {
        Component message = Component.text("[ModDetector] ", NamedTextColor.RED)
                .append(Component.text(offender.getUsername(), NamedTextColor.YELLOW))
                .append(Component.text(" detected using: ", NamedTextColor.GRAY))
                .append(Component.text(modName, NamedTextColor.GOLD))
                .append(Component.text(" (" + channel + ")", NamedTextColor.DARK_GRAY));

        for (Player admin : plugin.getServer().getAllPlayers()) {
            if (admin.hasPermission("moddetector.notify")) {
                admin.sendMessage(message);
            }
        }

        // Log plain text to console
        String plainText = PlainTextComponentSerializer.plainText().serialize(message);
        plugin.getLogger().info(plainText);
    }
}
