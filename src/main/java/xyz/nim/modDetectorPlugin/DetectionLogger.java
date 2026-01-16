package xyz.nim.modDetectorPlugin;

import com.velocitypowered.api.proxy.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final Path logFile;
    private final Path discoveredChannelsFile;

    private final Map<UUID, PlayerChannelData> playerDataCache = new ConcurrentHashMap<>();
    private final Set<String> discoveredChannels = ConcurrentHashMap.newKeySet();

    public DetectionLogger(ModDetectorPlugin plugin) {
        this.plugin = plugin;
        this.logFile = plugin.getDataDirectory().resolve("detections.json");
        this.discoveredChannelsFile = plugin.getDataDirectory().resolve("discovered-channels.json");
        ensureFilesExist();
        loadExistingData();
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
        if (Files.exists(discoveredChannelsFile)) {
            try {
                if (Files.size(discoveredChannelsFile) > 0) {
                    try (BufferedReader reader = Files.newBufferedReader(discoveredChannelsFile)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.startsWith("\"") && line.endsWith("\"")) {
                                String channel = line.replaceAll("^\"", "").replaceAll("\"[,]?$", "");
                                discoveredChannels.add(channel);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warn("Failed to load discovered channels: " + e.getMessage());
            }
        }

        if (Files.exists(logFile)) {
            try {
                if (Files.size(logFile) > 0) {
                    try (BufferedReader reader = Files.newBufferedReader(logFile)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            PlayerChannelData data = parsePlayerDataFromJson(line);
                            if (data != null) {
                                playerDataCache.put(data.uuid, data);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warn("Failed to load existing player data: " + e.getMessage());
            }
        }
    }

    private PlayerChannelData parsePlayerDataFromJson(String json) {
        try {
            String uuid = extractJsonValue(json, "uuid");
            String username = extractJsonValue(json, "username");
            String lastSeen = extractJsonValue(json, "lastSeen");

            if (uuid == null || username == null) return null;

            PlayerChannelData data = new PlayerChannelData(UUID.fromString(uuid), username);
            data.lastSeen = lastSeen;

            int modsStart = json.indexOf("\"mods\":[");
            if (modsStart == -1) {
                modsStart = json.indexOf("\"channels\":[");
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

        boolean isNewChannel = discoveredChannels.add(channel);

        writePlayerData();
        if (isNewChannel) {
            writeDiscoveredChannels();
        }
    }

    public void logDetection(Player player, Set<String> detectedMods, Instant joinTime, Instant leaveTime) {
        if (!plugin.getModFilterConfig().isTrackDetections()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
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

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logFile, StandardOpenOption.APPEND))) {
            writer.println(json);
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to write detection to log: " + e.getMessage());
        }

        if (plugin.getModFilterConfig().isDebug()) {
            plugin.getLogger().info("[DEBUG] Logged detection: " + json);
        }
    }

    private void writePlayerData() {
        ModFilterConfig config = plugin.getModFilterConfig();
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logFile))) {
            for (PlayerChannelData data : playerDataCache.values()) {
                writer.println(data.toJson(config));
            }
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to write player data: " + e.getMessage());
        }
    }

    private void writeDiscoveredChannels() {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(discoveredChannelsFile))) {
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
            plugin.getLogger().warn("Failed to write discovered channels: " + e.getMessage());
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

    public Path getLogFile() {
        return logFile;
    }

    public static class PlayerChannelData {
        public final UUID uuid;
        public String username;
        public final Set<String> channels = new LinkedHashSet<>();
        public String lastSeen;

        public PlayerChannelData(UUID uuid, String username) {
            this.uuid = uuid;
            this.username = username;
        }

        public String toJson(ModFilterConfig config) {
            Set<String> mods = new LinkedHashSet<>();
            for (String channel : channels) {
                String modName = config.getModName(channel);
                if (modName.equals(channel)) {
                    mods.add(channel);
                } else {
                    mods.add(modName);
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
