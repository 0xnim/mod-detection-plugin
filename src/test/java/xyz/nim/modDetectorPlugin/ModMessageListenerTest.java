package xyz.nim.modDetectorPlugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import java.util.logging.Logger;
import static org.mockito.Mockito.*;

class ModMessageListenerTest {
    @Test void loggingFailuresCannotPreventKickAndAlertsAreDeduplicated() {
        var plugin = mock(ModDetectorPlugin.class);
        var config = mock(ModFilterConfig.class);
        var logger = mock(DetectionLogger.class);
        var player = mock(Player.class);
        var scheduler = mock(BukkitScheduler.class);
        var console = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(console);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("TestPlayer");
        when(player.isOnline()).thenReturn(true);
        when(config.isLogAllChannels()).thenReturn(true);
        when(config.isKick()).thenReturn(true);
        when(config.shouldBlock(anyString())).thenReturn(true);
        when(config.getModName(anyString())).thenReturn("Test Mod");
        when(config.formatLogMessage(anyString(), anyString())).thenReturn("blocked");
        when(config.getKickMessageFormat()).thenReturn("Blocked: <mods>");
        doThrow(new IllegalStateException("disk error")).when(logger).logChannelRegistration(any(), any(), any());
        doThrow(new IllegalStateException("disk error")).when(logger).logDetection(any(), any(), any(), any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            var listener = new ModMessageListener(plugin, config, logger);
            listener.onChannelRegister(new PlayerRegisterChannelEvent(player, "test:main"));
            listener.onChannelRegister(new PlayerRegisterChannelEvent(player, "test:main"));
            var kick = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTaskLater(any(Plugin.class), kick.capture(), eq(20L));
            kick.getValue().run();
            verify(player).kick(any(net.kyori.adventure.text.Component.class));
            verify(console, times(1)).warning("blocked");
        }
    }
}
