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
    }

    @Subscribe
    public void onPlayerLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        sessionStartTimes.put(player.getUniqueId(), Instant.now());
    }

    @Subscribe
    public void onChannelRegister(PlayerChannelRegisterEvent event) {
        Player player = event.getPlayer();

        for (ChannelIdentifier channel : event.getChannels()) {
            String channelId = channel.getId();

            if (config.isDebug()) {
                plugin.getLogger().info("[DEBUG] Player " + player.getUsername() + " registered channel: " + channelId);
            }

            // Always track all channels in memory for /md info command
            UUID uuid = player.getUniqueId();
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
            String logMessage = config.formatLogMessage(player.getUsername(), channel);
            plugin.getLogger().warn(logMessage);
        }

        if (config.isNotifyAdmins()) {
            notifyAdmins(player, modName, channel);
        }

        if (action == ModFilterConfig.Action.KICK || action == ModFilterConfig.Action.BOTH) {
            if (pendingKicks.add(uuid)) {
                // Schedule kick with a small delay to batch multiple channel detections
                plugin.getServer().getScheduler()
                        .buildTask(plugin, () -> executeKick(player))
                        .delay(1, TimeUnit.SECONDS)
                        .schedule();
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

        plugin.getLogger().info(message.toString());
    }
}
