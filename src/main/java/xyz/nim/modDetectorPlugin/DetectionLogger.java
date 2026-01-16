package xyz.nim.modDetectorPlugin;

import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DetectionLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneId.of("UTC"));

    private final ModDetectorPlugin plugin;
    private final File logFile;
    private final File discoveredChannelsFile;

    // In-memory cache of player data for consolidated logging
    private final Map<UUID, PlayerChannelData> playerDataCache = new ConcurrentHashMap<>();

    // Set of all discovered channels ever seen
    private final Set<String> discoveredChannels = ConcurrentHashMap.newKeySet();

    public DetectionLogger(ModDetectorPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "detections.json");
        this.discoveredChannelsFile = new File(plugin.getDataFolder(), "discovered-channels.json");
        ensureFilesExist();
        loadExistingData();
    }

    private void ensureFilesExist() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        try {
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
            if (!discoveredChannelsFile.exists()) {
                discoveredChannelsFile.createNewFile();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to create log files: " + e.getMessage());
        }
    }

    private void loadExistingData() {
        // Load discovered channels
        if (discoveredChannelsFile.exists() && discoveredChannelsFile.length() > 0) {
            try (BufferedReader reader = new BufferedReader(new FileReader(discoveredChannelsFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("\"") && line.endsWith("\"")) {
                        // Remove quotes and trailing comma if present
                        String channel = line.replaceAll("^\"", "").replaceAll("\"[,]?$", "");
                        discoveredChannels.add(channel);
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to load discovered channels: " + e.getMessage());
            }
        }

        // Load existing player data from detections.json
        if (logFile.exists() && logFile.length() > 0) {
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    PlayerChannelData data = parsePlayerDataFromJson(line);
                    if (data != null) {
                        playerDataCache.put(data.uuid, data);
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to load existing player data: " + e.getMessage());
            }
        }
    }

    private PlayerChannelData parsePlayerDataFromJson(String json) {
        try {
            // Simple JSON parsing - extract uuid and mods
            String uuid = extractJsonValue(json, "uuid");
            String username = extractJsonValue(json, "username");
            String lastSeen = extractJsonValue(json, "lastSeen");

            if (uuid == null || username == null) return null;

            PlayerChannelData data = new PlayerChannelData(UUID.fromString(uuid), username);
            data.lastSeen = lastSeen;

            // Extract mods array (new format) or channels array (old format for backwards compat)
            int modsStart = json.indexOf("\"mods\":[");
            if (modsStart == -1) {
                modsStart = json.indexOf("\"channels\":["); // Fallback to old format
            }
            if (modsStart != -1) {
                int arrayStart = json.indexOf("[", modsStart);
                int arrayEnd = json.indexOf("]", arrayStart);
                if (arrayStart != -1 && arrayEnd != -1) {
                    String modsStr = json.substring(arrayStart + 1, arrayEnd);
                    for (String mod : modsStr.split(",")) {
                        mod = mod.trim().replaceAll("^\"|\"$", "");
                        if (!mod.isEmpty()) {
                            data.channels.add(mod);
                        }
                    }
                }
            }

            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) return null;

        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;

        return json.substring(start, end);
    }

    /**
     * Log a channel registration for a player (used when log-all-channels is enabled)
     */
    public void logChannelRegistration(Player player, String channel, Instant joinTime) {
        if (!plugin.getModFilterConfig().isLogAllChannels()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getName();

        // Update player data
        PlayerChannelData data = playerDataCache.computeIfAbsent(uuid,
                k -> new PlayerChannelData(uuid, username));
        data.username = username; // Update in case name changed
        data.channels.add(channel);
        data.lastSeen = TIMESTAMP_FORMAT.format(Instant.now());

        // Add to discovered channels
        boolean isNewChannel = discoveredChannels.add(channel);

        // Write updates to files
        writePlayerData();
        if (isNewChannel) {
            writeDiscoveredChannels();
        }
    }

    /**
     * Log detection of blocked mods (original behavior)
     */
    public void logDetection(Player player, Set<String> detectedMods, Instant joinTime, Instant leaveTime) {
        if (!plugin.getModFilterConfig().isTrackDetections()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        String joinTimeStr = joinTime != null ? TIMESTAMP_FORMAT.format(joinTime) : null;
        String leaveTimeStr = TIMESTAMP_FORMAT.format(leaveTime);

        long sessionDurationSeconds = 0;
        if (joinTime != null) {
            sessionDurationSeconds = Duration.between(joinTime, leaveTime).getSeconds();
        }

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"timestamp\":\"").append(timestamp).append("\",");
        json.append("\"uuid\":\"").append(uuid).append("\",");
        json.append("\"username\":\"").append(escapeJson(username)).append("\",");
        json.append("\"mods\":[");
        boolean first = true;
        for (String mod : detectedMods) {
            if (!first) json.append(",");
            json.append("\"").append(escapeJson(mod)).append("\"");
            first = false;
        }
        json.append("],");
        if (joinTimeStr != null) {
            json.append("\"sessionJoinTime\":\"").append(joinTimeStr).append("\",");
        }
        json.append("\"sessionLeaveTime\":\"").append(leaveTimeStr).append("\",");
        json.append("\"sessionDurationSeconds\":").append(sessionDurationSeconds);
        json.append("}");

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)))) {
            writer.println(json);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write detection to log: " + e.getMessage());
        }

        if (plugin.getModFilterConfig().isDebug()) {
            plugin.getLogger().info("[DEBUG] Logged detection: " + json);
        }
    }

    private void writePlayerData() {
        ModFilterConfig config = plugin.getModFilterConfig();
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, false)))) {
            for (PlayerChannelData data : playerDataCache.values()) {
                writer.println(data.toJson(config));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write player data: " + e.getMessage());
        }
    }

    private void writeDiscoveredChannels() {
        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(discoveredChannelsFile, false)))) {
            writer.println("[");
            boolean first = true;
            for (String channel : discoveredChannels) {
                if (!first) {
                    writer.println(",");
                }
                writer.print("  \"" + escapeJson(channel) + "\"");
                first = false;
            }
            writer.println();
            writer.println("]");
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write discovered channels: " + e.getMessage());
        }
    }

    public Set<String> getDiscoveredChannels() {
        return new HashSet<>(discoveredChannels);
    }

    public PlayerChannelData getPlayerData(UUID uuid) {
        return playerDataCache.get(uuid);
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    public File getLogFile() {
        return logFile;
    }

    public File getDiscoveredChannelsFile() {
        return discoveredChannelsFile;
    }

    /**
     * Data class for player channel information
     */
    public static class PlayerChannelData {
        public final UUID uuid;
        public String username;
        public final Set<String> channels = new LinkedHashSet<>(); // Preserve insertion order
        public String lastSeen;

        public PlayerChannelData(UUID uuid, String username) {
            this.uuid = uuid;
            this.username = username;
        }

        public String toJson(ModFilterConfig config) {
            // Resolve channels to mod names, grouping known mods and keeping raw strings for unknown
            Set<String> mods = new LinkedHashSet<>();
            for (String channel : channels) {
                String modName = config.getModName(channel);
                // If getModName returns the channel itself, it's unknown - keep the raw channel
                // Otherwise use the pretty mod name
                if (modName.equals(channel)) {
                    mods.add(channel); // Unknown channel, use raw string
                } else {
                    mods.add(modName); // Known mod, use pretty name (Set deduplicates)
                }
            }

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"uuid\":\"").append(uuid).append("\",");
            json.append("\"username\":\"").append(escapeJson(username)).append("\",");
            json.append("\"mods\":[");
            boolean first = true;
            for (String mod : mods) {
                if (!first) json.append(",");
                json.append("\"").append(escapeJson(mod)).append("\"");
                first = false;
            }
            json.append("],");
            json.append("\"lastSeen\":\"").append(lastSeen != null ? lastSeen : "").append("\"");
            json.append("}");
            return json.toString();
        }

        private static String escapeJson(String value) {
            if (value == null) return "";
            return value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t");
        }
    }
}
