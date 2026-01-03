package xyz.nim.modDetectorPlugin;

import org.bukkit.entity.Player;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

public class DetectionLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ModDetectorPlugin plugin;
    private final File logFile;

    public DetectionLogger(ModDetectorPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "detections.txt");
        ensureLogFileExists();
    }

    private void ensureLogFileExists() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
                writeHeader();
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to create detections.txt: " + e.getMessage());
            }
        }
    }

    private void writeHeader() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, false))) {
            writer.println("# ModDetector - Player Detection Log");
            writer.println("# Format: [timestamp] UUID | Username | Detected Mods");
            writer.println("# ================================================");
            writer.println();
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write header to detections.txt: " + e.getMessage());
        }
    }

    public void logDetection(Player player, Set<String> detectedMods) {
        if (!plugin.getModFilterConfig().isTrackDetections()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String username = player.getName();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String mods = String.join(", ", detectedMods);

        String logEntry = String.format("[%s] %s | %s | %s", timestamp, uuid, username, mods);

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)))) {
            writer.println(logEntry);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write detection to log: " + e.getMessage());
        }

        if (plugin.getModFilterConfig().isDebug()) {
            plugin.getLogger().info("[DEBUG] Logged detection: " + logEntry);
        }
    }

    public File getLogFile() {
        return logFile;
    }
}
