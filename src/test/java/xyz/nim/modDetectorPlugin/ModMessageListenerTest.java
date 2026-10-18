package xyz.nim.modDetectorPlugin;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModMessageListenerTest {
    ModDetectorPlugin plugin;
    ModFilterConfig config;
    DetectionLogger logger;
    Player player;
    EntityScheduler scheduler;
    ScheduledTask task;
    Logger console;
    ModMessageListener listener;

    @BeforeEach void setup() {
        plugin = mock(ModDetectorPlugin.class);
        config = mock(ModFilterConfig.class);
        logger = mock(DetectionLogger.class);
        player = mock(Player.class);
        scheduler = mock(EntityScheduler.class);
        task = mock(ScheduledTask.class);
        console = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(console);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("TestPlayer");
        when(player.isOnline()).thenReturn(true);
        when(player.getScheduler()).thenReturn(scheduler);
        when(scheduler.runDelayed(eq(plugin), any(), isNull(), eq(20L))).thenReturn(task);
        when(config.isLogAllChannels()).thenReturn(true);
        when(config.isKick()).thenReturn(true);
        when(config.shouldKick(anyString())).thenAnswer(i -> config.isKick());
        when(config.shouldBlock(anyString())).thenReturn(true);
        when(config.getModName(anyString())).thenReturn("Test Mod");
        when(config.formatLogMessage(anyString(), anyString())).thenReturn("blocked");
        when(config.getKickMessageFormat()).thenReturn("Blocked: <mods>");
        listener = new ModMessageListener(plugin, config, logger);
        listener.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
    }

    void register() {
        listener.onChannelRegister(new PlayerRegisterChannelEvent(player, "test:main"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Consumer<ScheduledTask> kick() {
        ArgumentCaptor<Consumer<ScheduledTask>> callback = ArgumentCaptor.forClass((Class) Consumer.class);
        verify(scheduler).runDelayed(eq(plugin), callback.capture(), isNull(), eq(20L));
        return callback.getValue();
    }

    @Test void loggingFailuresCannotPreventKickAndAlertsAreDeduplicated() {
        doThrow(new IllegalStateException("disk error")).when(logger).logChannelRegistration(any(), any(), any());
        doThrow(new IllegalStateException("disk error")).when(logger).logSession(any(), any(), any(), any(), any(), any());
        register(); register();
        kick().accept(task);
        verify(player).kick(any(Component.class));
        verify(console, times(1)).warning("blocked");
    }

    @Test void whitelistPreservesChannelTrackingAndSessionHistory() {
        when(config.isPlayerWhitelisted(player)).thenReturn(true);
        register();
        listener.onPluginMessageReceived("test:main", player, new byte[0]);
        assertTrue(listener.getAllRegisteredChannels().get(player.getUniqueId()).contains("test:main"));
        assertTrue(listener.getDetectedChannels().isEmpty());
        verify(logger).logChannelRegistration(eq(player), eq("test:main"), any());
        verifyNoInteractions(scheduler);
        listener.onPlayerQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        verify(logger).logSession(eq(player), eq(java.util.Set.of("Test Mod")), any(), any(), any(), any());
        assertTrue(listener.getAllRegisteredChannels().isEmpty());
    }

    @Test void newlyWhitelistedPlayerIsNotKicked() {
        register();
        when(config.isPlayerWhitelisted(player)).thenReturn(true);
        kick().accept(task);
        verify(player, never()).kick(any(Component.class));
        assertFalse(listener.getAllRegisteredChannels().isEmpty());
    }

    @Test void newBypassPermissionCancelsPendingKick() {
        register();
        when(player.hasPermission("moddetector.bypass")).thenReturn(true);
        kick().accept(task);
        verify(player, never()).kick(any(Component.class));
    }

    @Test void disablingKicksCancelsPendingKick() {
        register();
        when(config.isKick()).thenReturn(false);
        kick().accept(task);
        verify(player, never()).kick(any(Component.class));
    }

    @Test void oldKickCannotAffectReconnectedPlayer() {
        register();
        var oldKick = kick();
        listener.onPlayerQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        verify(task).cancel();
        listener.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
        register();
        oldKick.accept(task);
        verify(player, never()).kick(any(Component.class));
        assertFalse(listener.getDetectedChannels().isEmpty());
    }

    @Test void shutdownCancelsPendingKick() {
        register();
        listener.shutdown();
        verify(task).cancel();
    }

    @Test void adminPermissionAndMessageRunOnAdminScheduler() {
        var admin = mock(Player.class);
        var adminScheduler = mock(EntityScheduler.class);
        when(admin.getScheduler()).thenReturn(adminScheduler);
        when(config.shouldAlert(anyString())).thenReturn(true);
        when(admin.hasPermission("moddetector.notify")).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(admin));
            register();
            verify(admin, never()).hasPermission(anyString());
            verify(admin, never()).sendMessage(any(Component.class));
            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<Consumer<ScheduledTask>> callback = ArgumentCaptor.forClass((Class) Consumer.class);
            verify(adminScheduler).run(eq(plugin), callback.capture(), isNull());
            callback.getValue().accept(task);
            verify(admin).sendMessage(any(Component.class));
        }
    }
}
