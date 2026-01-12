package xyz.nim.modDetectorPlugin;

import com.velocitypowered.api.proxy.Player;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

public class DetectionLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneId.of("UTC"));

    private final ModDetectorPlugin plugin;
    private final Path logFile;

    public DetectionLogger(ModDetectorPlugin plugin) {
        this.plugin = plugin;
        this.logFile = plugin.getDataDirectory().resolve("detections.json");
        ensureLogFileExists();
    }

    private void ensureLogFileExists() {
        try {
            if (!Files.exists(plugin.getDataDirectory())) {
                Files.createDirectories(plugin.getDataDirectory());
            }

            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
            }
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to create detections.json: " + e.getMessage());
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

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(
                Files.newBufferedWriter(logFile, StandardOpenOption.APPEND)))) {
            writer.println(json);
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to write detection to log: " + e.getMessage());
        }

        if (plugin.getModFilterConfig().isDebug()) {
            plugin.getLogger().info("[DEBUG] Logged detection: " + json);
        }
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
}
