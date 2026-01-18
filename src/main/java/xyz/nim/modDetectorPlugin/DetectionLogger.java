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
        data.lastSeen = TIMESTAMP_FORMAT.format(Instant.now());

        // Resolve channel to mod name
        String modName = plugin.getModFilterConfig().getModName(channel);
        if (!modName.equals(channel)) {
            // Known mod - add to mods set
            data.mods.add(modName);
        } else {
            // Unknown channel - add to channels set
            data.channels.add(channel);
        }

        boolean isNewChannel = discoveredChannels.add(channel);

        // Mark for batched write instead of immediate write
        pendingPlayerDataWrite.set(true);
        if (isNewChannel) {
            pendingDiscoveredChannelsWrite.set(true);
        }
    }

    private static final int DELTA_THRESHOLD = 3; // Use delta if changes < this, otherwise full

    public void logDetection(Player player, Set<String> sessionMods, Set<String> sessionChannels, Instant joinTime, Instant leaveTime) {
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

        String joinTimeStr = joinTime != null ? TIMESTAMP_FORMAT.format(joinTime) : null;
        String leaveTimeStr = TIMESTAMP_FORMAT.format(leaveTime);

        // Normalize nulls to empty sets for comparison
        Set<String> currentMods = sessionMods != null ? sessionMods : new LinkedHashSet<>();

        // Get or create player data
        PlayerChannelData existingData = playerDataCache.get(uuid);

        if (existingData != null) {
            // Get previous session's mods to compute delta
            Set<String> previousMods = getModsForSession(existingData.sessions, existingData.sessions.size() - 1);

            // Compute delta
            Set<String> added = new LinkedHashSet<>(currentMods);
            added.removeAll(previousMods);
            Set<String> removed = new LinkedHashSet<>(previousMods);
            removed.removeAll(currentMods);

            int totalChanges = added.size() + removed.size();

            // Create appropriate session record
            SessionRecord session;
            if (totalChanges == 0) {
                // No changes - minimal record
                session = SessionRecord.unchanged(joinTimeStr, leaveTimeStr, sessionDurationSeconds, sessionChannels);
            } else if (totalChanges < DELTA_THRESHOLD) {
                // Small change - use delta
                session = SessionRecord.delta(joinTimeStr, leaveTimeStr, sessionDurationSeconds, added, removed, sessionChannels);
            } else {
                // Big change - use full
                session = SessionRecord.full(joinTimeStr, leaveTimeStr, sessionDurationSeconds, currentMods, sessionChannels);
            }

            // Update existing player record
            existingData.username = username;
            existingData.lastSeen = timestamp;
            existingData.totalTimePlayedSeconds += sessionDurationSeconds;
            existingData.sessionCount++;

            // Merge mods and channels into aggregate sets
            existingData.mods.addAll(currentMods);
            if (sessionChannels != null) {
                existingData.channels.addAll(sessionChannels);
            }

            if (existingData.sessions == null) {
                existingData.sessions = new ArrayList<>();
            }
            existingData.sessions.add(session);

            if (plugin.getModFilterConfig().isDebug()) {
                String format = totalChanges == 0 ? "unchanged" : (totalChanges < DELTA_THRESHOLD ? "delta" : "full");
                plugin.getLogger().info("[DEBUG] Updated detection record for " + username +
                        " (session #" + existingData.sessionCount + ", format: " + format + ", mods: " + currentMods + ")");
            }
        } else {
            // First detection - create new record with full mods
            SessionRecord session = SessionRecord.full(joinTimeStr, leaveTimeStr, sessionDurationSeconds, currentMods, sessionChannels);

            PlayerChannelData newData = new PlayerChannelData(uuid, username);
            newData.mods = new LinkedHashSet<>(currentMods);
            newData.channels = sessionChannels != null ? new LinkedHashSet<>(sessionChannels) : new LinkedHashSet<>();
            newData.lastSeen = timestamp;
            newData.firstSeen = timestamp;
            newData.totalTimePlayedSeconds = sessionDurationSeconds;
            newData.sessionCount = 1;
            newData.sessions = new ArrayList<>();
            newData.sessions.add(session);

            playerDataCache.put(uuid, newData);

            if (plugin.getModFilterConfig().isDebug()) {
                plugin.getLogger().info("[DEBUG] Created new detection record for " + username +
                        " (mods: " + currentMods + ")");
            }
        }

        // Mark for batched write
        pendingPlayerDataWrite.set(true);
    }

    /**
     * Reconstructs the full mod set for a given session index by walking from the last full snapshot.
     */
    public static Set<String> getModsForSession(List<SessionRecord> sessions, int sessionIndex) {
        if (sessions == null || sessions.isEmpty() || sessionIndex < 0 || sessionIndex >= sessions.size()) {
            return new LinkedHashSet<>();
        }

        // Find the last full snapshot at or before sessionIndex
        int lastFullIndex = -1;
        for (int i = sessionIndex; i >= 0; i--) {
            if (sessions.get(i).hasFull()) {
                lastFullIndex = i;
                break;
            }
        }

        if (lastFullIndex == -1) {
            // No full snapshot found - shouldn't happen if data is valid
            return new LinkedHashSet<>();
        }

        // Start with the full snapshot
        Set<String> mods = new LinkedHashSet<>(sessions.get(lastFullIndex).mods);

        // Apply deltas forward
        for (int i = lastFullIndex + 1; i <= sessionIndex; i++) {
            SessionRecord s = sessions.get(i);
            if (s.hasFull()) {
                // Another full snapshot - use it
                mods = new LinkedHashSet<>(s.mods);
            } else if (s.hasDelta()) {
                // Apply delta
                if (s.added != null) {
                    mods.addAll(s.added);
                }
                if (s.removed != null) {
                    mods.removeAll(s.removed);
                }
            }
            // If neither full nor delta, mods unchanged
        }

        return mods;
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
    // Uses delta compression: first session has full 'mods', subsequent sessions use +/- or full if big change
    public static class SessionRecord {
        public String joinTime;
        public String leaveTime;
        public long durationSeconds;
        // Full mod list (used for first session or when changes are large)
        public Set<String> mods;
        // Delta fields (used when only small changes from previous session)
        public Set<String> added;
        public Set<String> removed;
        // Channels (unknown) - always stored in full since they're typically few
        public Set<String> channels;

        public SessionRecord() {}

        // Constructor for full mod list
        public static SessionRecord full(String joinTime, String leaveTime, long durationSeconds,
                                         Set<String> mods, Set<String> channels) {
            SessionRecord r = new SessionRecord();
            r.joinTime = joinTime;
            r.leaveTime = leaveTime;
            r.durationSeconds = durationSeconds;
            r.mods = mods != null && !mods.isEmpty() ? new LinkedHashSet<>(mods) : null;
            r.channels = channels != null && !channels.isEmpty() ? new LinkedHashSet<>(channels) : null;
            return r;
        }

        // Constructor for delta (added/removed)
        public static SessionRecord delta(String joinTime, String leaveTime, long durationSeconds,
                                          Set<String> added, Set<String> removed, Set<String> channels) {
            SessionRecord r = new SessionRecord();
            r.joinTime = joinTime;
            r.leaveTime = leaveTime;
            r.durationSeconds = durationSeconds;
            r.added = added != null && !added.isEmpty() ? new LinkedHashSet<>(added) : null;
            r.removed = removed != null && !removed.isEmpty() ? new LinkedHashSet<>(removed) : null;
            r.channels = channels != null && !channels.isEmpty() ? new LinkedHashSet<>(channels) : null;
            return r;
        }

        // Constructor for unchanged mods (only time info)
        public static SessionRecord unchanged(String joinTime, String leaveTime, long durationSeconds, Set<String> channels) {
            SessionRecord r = new SessionRecord();
            r.joinTime = joinTime;
            r.leaveTime = leaveTime;
            r.durationSeconds = durationSeconds;
            r.channels = channels != null && !channels.isEmpty() ? new LinkedHashSet<>(channels) : null;
            return r;
        }

        public boolean hasFull() {
            return mods != null;
        }

        public boolean hasDelta() {
            return added != null || removed != null;
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
