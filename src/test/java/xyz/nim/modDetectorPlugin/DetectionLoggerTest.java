package xyz.nim.modDetectorPlugin;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DetectionLoggerTest {
    @TempDir Path directory;
    ModDetectorPlugin plugin;
    ModFilterConfig config;
    Player player;
    DetectionLogger logger;
    MockedStatic<Bukkit> bukkit;
    final Instant start = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach void setup() {
        plugin = mock(ModDetectorPlugin.class);
        config = mock(ModFilterConfig.class);
        player = mock(Player.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getModFilterConfig()).thenReturn(config);
        when(config.isLogAllChannels()).thenReturn(true);
        when(config.isTrackDetections()).thenReturn(true);
        when(config.getModName(anyString())).thenAnswer(i -> i.getArgument(0));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("TestPlayer");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
        logger = new DetectionLogger(plugin);
    }
    @AfterEach void cleanup() { bukkit.close(); }
    void session(String... mods) {
        logger.logDetection(player, new LinkedHashSet<>(List.of(mods)), Set.of(), start, start.plusSeconds(10));
    }
    @Test void channelRegistrationBeforeFirstSessionHasFullBaseline() {
        logger.logChannelRegistration(player, "test:channel", start);
        session("A");
        var data = logger.getPlayerData(player.getUniqueId());
        assertNotNull(data.firstSeen);
        assertTrue(data.sessions.getFirst().hasFull());
        assertEquals(Set.of("A"), DetectionLogger.getModsForSession(data.sessions, 0));
    }
    @Test void emptySnapshotsAndDeltasRoundTrip() throws Exception {
        session(); session("A"); session("A", "B", "C"); session(); session("D");
        logger.flushPendingWrites();
        var data = new Gson().fromJson(Files.readString(directory.resolve("detections.json")), DetectionLogger.PlayerChannelData.class);
        var expected = List.of(Set.of(), Set.of("A"), Set.of("A", "B", "C"), Set.of(), Set.of("D"));
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), DetectionLogger.getModsForSession(data.sessions, i));
    }
    @Test void retentionRebasesDeltasAndKeepsLifetimeTotals() {
        when(config.getMaxSessionsPerPlayer()).thenReturn(2);
        session("A"); session("A", "B"); session("B");
        var data = logger.getPlayerData(player.getUniqueId());
        assertEquals(2, data.sessions.size());
        assertEquals(3, data.sessionCount);
        assertEquals(30, data.totalTimePlayedSeconds);
        assertEquals(Set.of("A", "B"), DetectionLogger.getModsForSession(data.sessions, 0));
        assertEquals(Set.of("B"), DetectionLogger.getModsForSession(data.sessions, 1));
    }
    @Test void unchangedSessionsAndReloadRetentionKeepHistory() {
        session("A"); session("A"); session("A", "B");
        assertFalse(logger.getPlayerData(player.getUniqueId()).sessions.get(1).hasFull());
        when(config.getMaxSessionsPerPlayer()).thenReturn(2);
        logger.applyRetention();
        logger.shutdown();
        logger = new DetectionLogger(plugin);
        var data = logger.getPlayerData(player.getUniqueId());
        assertEquals(2, data.sessions.size());
        assertEquals(Set.of("A"), DetectionLogger.getModsForSession(data.sessions, 0));
        assertEquals(Set.of("A", "B"), DetectionLogger.getModsForSession(data.sessions, 1));
    }
    @Test void failedWriteRetriesWithoutAnotherEvent() throws Exception {
        session("A");
        Path temp = directory.resolve("detections.json.tmp");
        Files.createDirectory(temp);
        logger.flushPendingWrites();
        Files.delete(temp);
        logger.flushPendingWrites();
        assertTrue(Files.readString(directory.resolve("detections.json")).contains("TestPlayer"));
    }
    @Test void returnedRecordsDoNotExposeLiveState() {
        session("A");
        logger.getPlayerData(player.getUniqueId()).sessions.getFirst().mods.clear();
        assertEquals(Set.of("A"), logger.getPlayerData(player.getUniqueId()).sessions.getFirst().mods);
    }
    @Test void concurrentWritesKeepConsistentRecords() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> writes = executor.submit(() -> { for (int i = 0; i < 200; i++) logger.flushPendingWrites(); });
            for (int i = 0; i < 200; i++) session("A", "M" + i);
            writes.get(10, TimeUnit.SECONDS);
        }
        logger.shutdown();
        var data = new Gson().fromJson(Files.readString(directory.resolve("detections.json")), DetectionLogger.PlayerChannelData.class);
        assertEquals(200, data.sessions.size());
        assertEquals(200, data.sessionCount);
        assertEquals(Set.of("A", "M199"), DetectionLogger.getModsForSession(data.sessions, 199));
    }
    @Test void legacyNullSessionsLoadSafely() throws Exception {
        Files.writeString(directory.resolve("detections.json"), "{\"uuid\":\"" + player.getUniqueId() + "\",\"sessions\":null,\"mods\":null,\"channels\":null}\n");
        logger = new DetectionLogger(plugin);
        session("A");
        assertEquals(Set.of("A"), DetectionLogger.getModsForSession(logger.getPlayerData(player.getUniqueId()).sessions, 0));
    }
}
