package xyz.nim.modDetectorPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
    private final Map<UUID, Set<String>> kickCandidates = new ConcurrentHashMap<>();
    private final Set<UUID> pendingKicks = ConcurrentHashMap.newKeySet();

    public ModMessageListener(ModDetectorPlugin plugin, ModFilterConfig config, DetectionLogger detectionLogger) {
        this.plugin = plugin;
        this.config = config;
        this.detectionLogger = detectionLogger;
        startCleanupTask();
    }

    private void startCleanupTask() {
        // Clean up stale entries every 5 minutes (6000 ticks)
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupStaleEntries, 6000L, 6000L);
    }

    private void cleanupStaleEntries() {
        // Clean up entries for players who are no longer online
        Set<UUID> onlineUuids = ConcurrentHashMap.newKeySet();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineUuids.add(player.getUniqueId());
        }

        // Remove tracking data for offline players
        sessionStartTimes.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        detectedChannels.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        allRegisteredChannels.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        pendingKicks.removeIf(uuid -> !onlineUuids.contains(uuid));
        kickCandidates.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));

        if (config.isDebug()) {
            plugin.getLogger().info("[DEBUG] Cleaned up stale tracking entries");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessionStartTimes.put(player.getUniqueId(), Instant.now());
        allRegisteredChannels.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
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
                try {
                    detectionLogger.logChannelRegistration(player, channel, sessionStartTimes.get(uuid));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Failed to log channel: " + e.getMessage());
                }
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

        // Get all channels registered this session
        Set<String> channels = allRegisteredChannels.remove(uuid);
        detectedChannels.remove(uuid);
        pendingKicks.remove(uuid);
        kickCandidates.remove(uuid);

        // Log session with all mods and channels
        if (channels != null) {
            // Resolve channels into known mods and unknown channels
            Set<String> sessionMods = new java.util.LinkedHashSet<>();
            Set<String> sessionUnknownChannels = new java.util.LinkedHashSet<>();

            for (String channel : channels) {
                String modName = config.getModName(channel);
                if (!modName.equals(channel)) {
                    // Known mod
                    sessionMods.add(modName);
                } else {
                    // Unknown channel
                    sessionUnknownChannels.add(channel);
                }
            }

            logSession(player, sessionMods, sessionUnknownChannels, channels, joinTime, leaveTime);
        }
    }

    private void handleBlockedChannel(Player player, String channel) {
        UUID uuid = player.getUniqueId();

        kickCandidates.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(channel);
        if (isExcepted(player, channel)) return;
        if (config.shouldKick(channel) && pendingKicks.add(uuid)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> executeKick(player), 20L);
        }
        String modName = config.getModName(channel);
        Set<String> playerMods = detectedChannels.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        boolean isNewDetection = playerMods.add(modName);

        if (!isNewDetection) return;

        // Always log to console
        String logMessage = config.formatLogMessage(player.getName(), channel);
        plugin.getLogger().warning(logMessage);

        if (config.shouldAlert(channel)) {
            notifyAdmins(player, modName, channel);
        }

        // Note: Session logging now happens on quit via onPlayerQuit
    }

    private void executeKick(Player player) {
        UUID uuid = player.getUniqueId();
        pendingKicks.remove(uuid);

        if (!player.isOnline()) {
            detectedChannels.remove(uuid);
            sessionStartTimes.remove(uuid);
            allRegisteredChannels.remove(uuid);
            kickCandidates.remove(uuid);
            return;
        }

        Set<String> candidates = kickCandidates.getOrDefault(uuid, Set.of());
        Set<String> blockedMods = new java.util.TreeSet<>();
        if (!player.hasPermission("moddetector.bypass")) {
            for (String channel : candidates) {
                if (config.shouldKick(channel) && !isExcepted(player, channel)) blockedMods.add(config.getModName(channel));
            }
        }
        if (blockedMods.isEmpty()) {
            return;
        }

        Instant joinTime = sessionStartTimes.remove(uuid);
        Instant kickTime = Instant.now();

        // Get all channels and resolve to mods/unknown
        Set<String> allChannels = allRegisteredChannels.remove(uuid);
        Set<String> sessionMods = new java.util.LinkedHashSet<>();
        Set<String> sessionUnknownChannels = new java.util.LinkedHashSet<>();

        if (allChannels != null) {
            for (String channel : allChannels) {
                String modName = config.getModName(channel);
                if (!modName.equals(channel)) {
                    sessionMods.add(modName);
                } else {
                    sessionUnknownChannels.add(channel);
                }
            }
        } else {
            // Fallback to blocked mods if no channel data
            sessionMods.addAll(blockedMods);
        }

        kickCandidates.remove(uuid);
        logSession(player, sessionMods, sessionUnknownChannels,
                allChannels == null ? Set.of() : allChannels, joinTime, kickTime);

        String modList = String.join(", ", blockedMods);
        Component kickComponent = miniMessage.deserialize(
                config.getKickMessageFormat(),
                Placeholder.unparsed("mods", modList)
        );

        player.kick(kickComponent);
    }

    private void logSession(Player player, Set<String> mods, Set<String> channels, Set<String> registered, Instant join, Instant leave) {
        try {
            detectionLogger.logSession(player, mods, channels, registered, join, leave);
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Failed to log session for " + player.getName(), e);
        }
    }

    public boolean isExcepted(Player player, String channel) {
        return plugin.getTemporaryExceptions() != null
                && plugin.getTemporaryExceptions().allows(player.getUniqueId(), config.getModId(channel));
    }

    public void recheck(Player player) {
        if (player.hasPermission("moddetector.bypass")) return;
        Set<String> observed = new java.util.HashSet<>(allRegisteredChannels.getOrDefault(player.getUniqueId(), Set.of()));
        observed.addAll(kickCandidates.getOrDefault(player.getUniqueId(), Set.of()));
        for (String channel : observed) {
            if (config.shouldBlock(channel)) handleBlockedChannel(player, channel);
        }
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

        // Log plain text to console
        String plainText = PlainTextComponentSerializer.plainText().serialize(message);
        plugin.getLogger().info(plainText);
    }
}
