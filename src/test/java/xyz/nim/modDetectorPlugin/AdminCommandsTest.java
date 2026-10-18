package xyz.nim.modDetectorPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminCommandsTest {
    ModDetectorPlugin plugin;
    ModFilterConfig config;
    DetectionLogger logger;
    ModMessageListener listener;
    CommandSender sender;
    AdminCommands commands;
    MockedStatic<Bukkit> bukkit;
    List<String> output;
    DetectionLogger.PlayerChannelData data;
    @BeforeEach void setup() {
        plugin = mock(ModDetectorPlugin.class);
        config = mock(ModFilterConfig.class);
        logger = mock(DetectionLogger.class);
        listener = mock(ModMessageListener.class);
        sender = mock(CommandSender.class);
        when(sender.hasPermission("moddetector.admin")).thenReturn(true);
        output = new ArrayList<>();
        doAnswer(i -> { output.add(PlainTextComponentSerializer.plainText().serialize(i.getArgument(0))); return null; })
                .when(sender).sendMessage(any(Component.class));
        data = new DetectionLogger.PlayerChannelData(UUID.randomUUID(), "OfflinePlayer");
        data.mods.add("Jade");
        data.sessions.add(DetectionLogger.SessionRecord.full("2026-01-01T23:00:00Z", "2026-01-02T01:00:00Z", 7200, Set.of("Jade"), Set.of()));
        data.sessions.getFirst().registeredChannels = Set.of("jade:main");
        data.sessions.add(DetectionLogger.SessionRecord.delta("2026-01-03T00:00:00Z", "2026-01-03T01:00:00Z", 3600, Set.of(), Set.of("Jade"), Set.of()));
        data.sessions.getLast().registeredChannels = Set.of();
        data.sessionCount = 2;
        when(logger.getPlayers()).thenReturn(List.of(data));
        when(logger.getPlayerData(data.uuid)).thenReturn(data);
        bukkit = mockStatic(Bukkit.class);
        var global = mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(global);
        doAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return null; }).when(global).execute(eq(plugin), any());
        commands = new AdminCommands(plugin, config, logger, listener);
    }
    @AfterEach void cleanup() { bukkit.close(); }
    @Test void offlineInfoAndChangesUseStoredHistory() {
        assertEquals(1, commands.execute(sender, "info", "OfflinePlayer"));
        assertTrue(output.stream().anyMatch(s -> s.contains("online: false")));
        output.clear();
        assertEquals(1, commands.execute(sender, "changes", data.uuid.toString()));
        assertTrue(output.stream().anyMatch(s -> s.contains("Mod removed: Jade")));
        assertTrue(output.stream().anyMatch(s -> s.contains("Channel removed: jade:main")));
    }
    @Test void searchMatchesKnownChannelsAndDatesAcrossMidnight() {
        assertEquals(1, commands.execute(sender, "search", "channel jade:main"));
        assertTrue(output.stream().anyMatch(s -> s.contains("OfflinePlayer")));
        output.clear();
        assertEquals(1, commands.execute(sender, "search", "date 2026-01-02"));
        assertTrue(output.stream().anyMatch(s -> s.contains("OfflinePlayer")));
        assertEquals(0, commands.execute(sender, "search", "date invalid"));
        assertEquals(0, commands.execute(sender, "search", "mod Jade 999999999"));
    }
    @Test void duplicateSavedNamesRequireUuid() {
        var second = new DetectionLogger.PlayerChannelData(UUID.randomUUID(), data.username);
        when(logger.getPlayers()).thenReturn(List.of(data, second));
        assertEquals(0, commands.execute(sender, "info", "OfflinePlayer"));
        assertTrue(output.stream().anyMatch(s -> s.contains("Multiple saved players")));
    }
    @Test void previewAccountsForBypassAndDoesNotReloadOrChangeConfiguration() {
        Player player = mock(Player.class);
        var scheduler = mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        doAnswer(i -> {
            java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> callback = i.getArgument(1);
            callback.accept(null);
            return mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
        }).when(scheduler).run(eq(plugin), any(), any());
        when(player.getUniqueId()).thenReturn(data.uuid);
        when(player.getName()).thenReturn("OnlinePlayer");
        when(player.hasPermission("moddetector.bypass")).thenReturn(true);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        when(listener.getAllRegisteredChannels()).thenReturn(Map.of(data.uuid, Set.of("jade:main")));
        when(config.previewBlocked("jade:main", "jade:*")).thenReturn(true);
        when(config.isKick()).thenReturn(true);
        when(config.getAction("jade:main")).thenReturn(ModFilterConfig.Action.KICK);
        assertEquals(1, commands.execute(sender, "preview", "jade:*"));
        assertTrue(output.stream().anyMatch(s -> s.contains("would kick=false")));
        verify(config, never()).load();
        verify(player, never()).kick(any(Component.class));
    }
    @Test void parsesQuotedNamesAndRejectsInvalidArgumentsAndPermissions() {
        assertEquals(List.of("mod", "Simple Voice Chat", "2"), AdminCommands.arguments("mod \"Simple Voice Chat\" 2"));
        assertEquals(List.of("example:a?*"), AdminCommands.arguments("example:a?*"));
        assertThrows(IllegalArgumentException.class, () -> AdminCommands.arguments("\"unfinished"));
        assertThrows(IllegalArgumentException.class, () -> AdminCommands.arguments("\"joined\"text"));
        when(sender.hasPermission("moddetector.admin")).thenReturn(false);
        assertEquals(0, commands.execute(sender, "info", "OfflinePlayer"));
        verify(logger, never()).getPlayers();
    }
    @Test void previewChecksWhitelistOnOwningScheduler() {
        Player player = mock(Player.class);
        var scheduler = mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        when(player.getUniqueId()).thenReturn(data.uuid);
        when(player.getName()).thenReturn("OnlinePlayer");
        when(scheduler.run(eq(plugin), any(), any())).thenReturn(mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class));
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
        when(listener.getAllRegisteredChannels()).thenReturn(Map.of(data.uuid, Set.of("jade:main")));
        when(config.previewBlocked("jade:main", "jade:*")).thenReturn(true);
        when(config.isKick()).thenReturn(true);
        when(config.getAction("jade:main")).thenReturn(ModFilterConfig.Action.KICK);
        when(config.isPlayerWhitelisted(player)).thenReturn(true);
        assertEquals(1, commands.execute(sender, "preview", "jade:*"));
        verify(player, never()).hasPermission(anyString());
        assertTrue(output.isEmpty());
        @SuppressWarnings({"unchecked", "rawtypes"})
        org.mockito.ArgumentCaptor<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> callback =
                org.mockito.ArgumentCaptor.forClass((Class) java.util.function.Consumer.class);
        verify(scheduler).run(eq(plugin), callback.capture(), any());
        callback.getValue().accept(null);
        assertTrue(output.stream().anyMatch(s -> s.contains("would kick=false")));
    }
}
