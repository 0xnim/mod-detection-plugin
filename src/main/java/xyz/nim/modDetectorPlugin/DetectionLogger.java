package xyz.nim.modDetectorPlugin;

import com.velocitypowered.api.proxy.Player;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

public class DetectionLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ModDetectorPlugin plugin;
    private final Path logFile;

    public DetectionLogger(ModDetectorPlugin plugin) {
        this.plugin = plugin;
        this.logFile = plugin.getDataDirectory().resolve("detections.txt");
        ensureLogFileExists();
    }

    private void ensureLogFileExists() {
        try {
            if (!Files.exists(plugin.getDataDirectory())) {
                Files.createDirectories(plugin.getDataDirectory());
            }

            if (!Files.exists(logFile)) {
                Files.createFile(logFile);
                writeHeader();
            }
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to create detections.txt: " + e.getMessage());
        }
    }

    private void writeHeader() {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logFile))) {
            writer.println("# ModDetector - Player Detection Log");
            writer.println("# Format: [timestamp] UUID | Username | Detected Mods");
            writer.println("# ================================================");
            writer.println();
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to write header to detections.txt: " + e.getMessage());
        }
    }

    public void logDetection(Player player, Set<String> detectedMods) {
        if (!plugin.getModFilterConfig().isTrackDetections()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getUsername();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String mods = String.join(", ", detectedMods);

        String logEntry = String.format("[%s] %s | %s | %s", timestamp, uuid, username, mods);

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(
                Files.newBufferedWriter(logFile, StandardOpenOption.APPEND)))) {
            writer.println(logEntry);
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to write detection to log: " + e.getMessage());
        }

        if (plugin.getModFilterConfig().isDebug()) {
            plugin.getLogger().info("[DEBUG] Logged detection: " + logEntry);
        }
    }

    public Path getLogFile() {
        return logFile;
    }
}
