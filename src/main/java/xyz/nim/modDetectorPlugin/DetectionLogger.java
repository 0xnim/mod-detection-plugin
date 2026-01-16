package xyz.nim.modDetectorPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.velocitypowered.api.proxy.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class DetectionLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneId.of("UTC"));

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private final ModDetectorPlugin plugin;
    private final Path logFile;
    private final Path discoveredChannelsFile;

    private final Map<UUID, PlayerChannelData> playerDataCache = new ConcurrentHashMap<>();
    private final Set<String> discoveredChannels = ConcurrentHashMap.newKeySet();

    // Batched write support
    private final AtomicBoolean pendingPlayerDataWrite = new AtomicBoolean(false);
    private final AtomicBoolean pendingDiscoveredChannelsWrite = new AtomicBoolean(false);

    public DetectionLogger(ModDetectorPlugin plugin) {
        this.plugin = plugin;
        this.logFile = plugin.getDataDirectory().resolve("detections.json");
        this.discoveredChannelsFile = plugin.getDataDirectory().resolve("discovered-channels.json");
        ensureFilesExist();
        loadExistingData();
        startBatchedWriteScheduler();
    }

    private void ensureFilesExist() {
        try {
            if (!Files.exists(plugin.getDataDirectory())) {
                Files.createDirectories(plugin.getDataDirectory());
            }
            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
            if (!Files.exists(discoveredChannelsFile)) {
                Files.createFile(discoveredChannelsFile);
            }
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to create log files: " + e.getMessage());
        }
    }

    private void loadExistingData() {
        // Load discovered channels
        if (Files.exists(discoveredChannelsFile)) {
            try {
                if (Files.size(discoveredChannelsFile) > 0) {
                    String content = Files.readString(discoveredChannelsFile);
                    Type setType = new TypeToken<Set<String>>() {}.getType();
                    Set<String> loaded = GSON.fromJson(content, setType);
                    if (loaded != null) {
                        discoveredChannels.addAll(loaded);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warn("Failed to load discovered channels: " + e.getMessage());
            }
        }

        // Load player data (JSON lines format)
        if (Files.exists(logFile)) {
            try {
                if (Files.size(logFile) > 0) {
                    try (BufferedReader reader = Files.newBufferedReader(logFile)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            try {
                                PlayerChannelData data = GSON.fromJson(line, PlayerChannelData.class);
                                if (data != null && data.uuid != null) {
                                    playerDataCache.put(data.uuid, data);
                                }
                            } catch (Exception e) {
                                // Skip malformed lines
                            }
                        }
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warn("Failed to load existing player data: " + e.getMessage());
            }
        }
    }

    private void startBatchedWriteScheduler() {
        // Flush pending writes every 30 seconds
        plugin.getServer().getScheduler()
                .buildTask(plugin, this::flushPendingWrites)
                .repeat(30, TimeUnit.SECONDS)
                .schedule();
    }

    private void flushPendingWrites() {
        if (pendingPlayerDataWrite.compareAndSet(true, false)) {
            writePlayerDataAtomic();
        }
        if (pendingDiscoveredChannelsWrite.compareAndSet(true, false)) {
            writeDiscoveredChannelsAtomic();
        }
    }

    public void logChannelRegistration(Player player, String channel, Instant joinTime) {
        if (!plugin.getModFilterConfig().isLogAllChannels()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getUsername();

        PlayerChannelData data = playerDataCache.computeIfAbsent(uuid,
                k -> new PlayerChannelData(uuid, username));
        data.username = username;
        data.channels.add(channel);
        data.lastSeen = TIMESTAMP_FORMAT.format(Instant.now());

        // Resolve channel to mod name and add to mods set
        String modName = plugin.getModFilterConfig().getModName(channel);
        if (!modName.equals(channel)) {
            // Only add if it resolved to a known mod name
            data.mods.add(modName);
        }

        boolean isNewChannel = discoveredChannels.add(channel);

        // Mark for batched write instead of immediate write
        pendingPlayerDataWrite.set(true);
        if (isNewChannel) {
            pendingDiscoveredChannelsWrite.set(true);
        }
    }

    public void logDetection(Player player, Set<String> detectedMods, Instant joinTime, Instant leaveTime) {
        if (!plugin.getModFilterConfig().isTrackDetections()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());

        long sessionDurationSeconds = 0;
        if (joinTime != null && leaveTime != null) {
            sessionDurationSeconds = Duration.between(joinTime, leaveTime).getSeconds();
        }

        // Get or create player data
        PlayerChannelData existingData = playerDataCache.get(uuid);

        if (existingData != null && existingData.mods != null && existingData.mods.equals(detectedMods)) {
            // Mods haven't changed - just update session info
            existingData.username = username;
            existingData.lastSeen = timestamp;
            existingData.totalTimePlayedSeconds += sessionDurationSeconds;
            existingData.sessionCount++;

            // Add session record
            SessionRecord session = new SessionRecord(
                    joinTime != null ? TIMESTAMP_FORMAT.format(joinTime) : null,
                    TIMESTAMP_FORMAT.format(leaveTime),
                    sessionDurationSeconds
            );
            if (existingData.sessions == null) {
                existingData.sessions = new ArrayList<>();
            }
            existingData.sessions.add(session);

            if (plugin.getModFilterConfig().isDebug()) {
                plugin.getLogger().info("[DEBUG] Updated existing detection record for " + username +
                        " (same mods, session #" + existingData.sessionCount + ")");
            }
        } else {
            // New mods or first detection - create/update full record
            PlayerChannelData newData = new PlayerChannelData(uuid, username);
            newData.mods = new LinkedHashSet<>(detectedMods);
            newData.lastSeen = timestamp;
            newData.firstSeen = timestamp;
            newData.totalTimePlayedSeconds = sessionDurationSeconds;
            newData.sessionCount = 1;
            newData.sessions = new ArrayList<>();
            newData.sessions.add(new SessionRecord(
                    joinTime != null ? TIMESTAMP_FORMAT.format(joinTime) : null,
                    TIMESTAMP_FORMAT.format(leaveTime),
                    sessionDurationSeconds
            ));

            playerDataCache.put(uuid, newData);

            if (plugin.getModFilterConfig().isDebug()) {
                plugin.getLogger().info("[DEBUG] Created new detection record for " + username +
                        " (mods: " + detectedMods + ")");
            }
        }

        // Mark for batched write
        pendingPlayerDataWrite.set(true);
    }

    private void writePlayerDataAtomic() {
        Path tempFile = logFile.resolveSibling(logFile.getFileName() + ".tmp");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(tempFile))) {
            for (PlayerChannelData data : playerDataCache.values()) {
                writer.println(GSON.toJson(data));
            }
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to write player data: " + e.getMessage());
            return;
        }

        try {
            Files.move(tempFile, logFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to atomically move player data file: " + e.getMessage());
            // Fallback: try regular move
            try {
                Files.move(tempFile, logFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                plugin.getLogger().warn("Failed to move player data file: " + e2.getMessage());
            }
        }
    }

    private void writeDiscoveredChannelsAtomic() {
        Path tempFile = discoveredChannelsFile.resolveSibling(discoveredChannelsFile.getFileName() + ".tmp");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(tempFile))) {
            writer.println(GSON.toJson(discoveredChannels));
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to write discovered channels: " + e.getMessage());
            return;
        }

        try {
            Files.move(tempFile, discoveredChannelsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to atomically move discovered channels file: " + e.getMessage());
            try {
                Files.move(tempFile, discoveredChannelsFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                plugin.getLogger().warn("Failed to move discovered channels file: " + e2.getMessage());
            }
        }
    }

    public Set<String> getDiscoveredChannels() {
        return new HashSet<>(discoveredChannels);
    }

    public PlayerChannelData getPlayerData(UUID uuid) {
        return playerDataCache.get(uuid);
    }

    public Path getLogFile() {
        return logFile;
    }

    public void shutdown() {
        // Flush any pending writes on shutdown
        flushPendingWrites();
    }

    // Session record for tracking individual play sessions
    public static class SessionRecord {
        public String joinTime;
        public String leaveTime;
        public long durationSeconds;

        public SessionRecord() {}

        public SessionRecord(String joinTime, String leaveTime, long durationSeconds) {
            this.joinTime = joinTime;
            this.leaveTime = leaveTime;
            this.durationSeconds = durationSeconds;
        }
    }

    // POJO for player channel data
    public static class PlayerChannelData {
        public UUID uuid;
        public String username;
        public Set<String> channels = new LinkedHashSet<>();  // For log-all-channels mode
        public Set<String> mods = new LinkedHashSet<>();      // For detection tracking
        public String firstSeen;
        public String lastSeen;
        public long totalTimePlayedSeconds;
        public int sessionCount;
        public List<SessionRecord> sessions;

        // Required for Gson deserialization
        public PlayerChannelData() {}

        public PlayerChannelData(UUID uuid, String username) {
            this.uuid = uuid;
            this.username = username;
        }
    }
}
