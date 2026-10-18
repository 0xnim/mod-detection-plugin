package xyz.nim.modDetectorPlugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModFilterConfigTest {
    ModFilterConfig config(String mode, List<String> ids, List<String> patterns) {
        var plugin = mock(ModDetectorPlugin.class);
        var yaml = new YamlConfiguration();
        yaml.set("mode", mode);
        yaml.set("blocked-mods", ids);
        yaml.set("custom-patterns", patterns);
        when(plugin.getConfig()).thenReturn(yaml);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getResource("mods.yml")).thenAnswer(i -> getClass().getResourceAsStream("/mods.yml"));
        var result = new ModFilterConfig(plugin);
        result.load();
        return result;
    }
    @Test void specificIdentityDoesNotChangeBroadBlockingRule() {
        var config = config("blacklist", List.of("servux"), List.of());
        assertEquals("Litematica", config.getModName("servux:litematics"));
        assertTrue(config.shouldBlock("servux:litematics"));
        assertTrue(config.explain("servux:litematics").contains("rule servux (servux:*)"));
    }
    @Test void whitelistAllowsMatchesAndBlocksUnknownChannels() {
        var config = config("whitelist", List.of("jade"), List.of());
        assertFalse(config.shouldBlock("jade:test"));
        assertTrue(config.shouldBlock("unknown:test"));
        assertTrue(config.explain("unknown:test").contains("no configured rule matched"));
    }
    @Test void wildcardAndRegexCharactersAreHandledLiterally() {
        var config = config("blacklist", List.of(), List.of("example:a.b?*"));
        assertTrue(config.shouldBlock("example:a.b1rest"));
        assertFalse(config.shouldBlock("example:axb1rest"));
        assertFalse(config.shouldBlock("example:a.b"));
    }
    @Test void previewAddsPatternWithoutChangingLoadedRulesInEitherMode() {
        var blacklist = config("blacklist", List.of("jade"), List.of());
        assertTrue(blacklist.previewBlocked("new:main", "new:*"));
        assertFalse(blacklist.shouldBlock("new:main"));
        assertTrue(blacklist.previewBlocked("jade:main", "new:*"));
        var whitelist = config("whitelist", List.of("jade"), List.of());
        assertFalse(whitelist.previewBlocked("new:main", "new:*"));
        assertTrue(whitelist.shouldBlock("new:main"));
        assertFalse(whitelist.previewBlocked("jade:main", "new:*"));
    }
    @Test void actionsRespectMasterSwitchesAndValidationReportsBadRules() {
        var plugin = mock(ModDetectorPlugin.class);
        var yaml = new YamlConfiguration();
        yaml.set("mode", "blacklist");
        yaml.set("blocked-mods", List.of("jade", "missing"));
        yaml.set("custom-patterns", List.of("jade:*"));
        yaml.set("mod-actions.jade", "log");
        yaml.set("mod-actions.missing", "kick");
        when(plugin.getConfig()).thenReturn(yaml);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getResource("mods.yml")).thenAnswer(i -> getClass().getResourceAsStream("/mods.yml"));
        var config = new ModFilterConfig(plugin);
        config.load();
        assertTrue(config.shouldBlock("jade:main"));
        assertFalse(config.shouldKick("jade:main"));
        assertFalse(config.shouldAlert("jade:main"));
        assertTrue(config.getWarnings().stream().anyMatch(w -> w.contains("Unknown mod ID")));
        assertTrue(config.getWarnings().stream().anyMatch(w -> w.contains("Duplicate filter pattern")));
        yaml.set("mod-actions.jade", "alert");
        config.load();
        assertTrue(config.shouldAlert("jade:main"));
        assertFalse(config.shouldKick("jade:main"));
        yaml.set("mod-actions.jade", "kick");
        config.load();
        assertTrue(config.shouldKick("jade:main"));
        yaml.set("kick", false);
        yaml.set("notify-admins", false);
        config.load();
        assertFalse(config.shouldKick("jade:main"));
        assertFalse(config.shouldAlert("jade:main"));
        yaml.set("mod-actions.jade", "invalid");
        config.load();
        assertTrue(config.getWarnings().stream().anyMatch(w -> w.contains("Invalid action")));
    }

    @Test void whitelistAcceptsNamesAndUuidsAndReloadRemovesOldEntries() {
        var plugin = mock(ModDetectorPlugin.class);
        var yaml = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(yaml);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var player = mock(org.bukkit.entity.Player.class);
        var uuid = java.util.UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("TestPlayer");
        var config = new ModFilterConfig(plugin);
        yaml.set("whitelisted-players", List.of("  TESTPLAYER  ", ""));
        config.load();
        assertTrue(config.isPlayerWhitelisted(player));
        yaml.set("whitelisted-players", List.of(uuid.toString().toUpperCase(java.util.Locale.ROOT)));
        config.load();
        assertTrue(config.isPlayerWhitelisted(player));
        yaml.set("whitelisted-players", List.of());
        config.load();
        assertFalse(config.isPlayerWhitelisted(player));
        when(player.isOp()).thenReturn(true);
        assertFalse(config.isPlayerWhitelisted(player));
        yaml.set("whitelist-operators", true);
        config.load();
        assertTrue(config.isPlayerWhitelisted(player));
    }
    @Test void modDefinitionsRemainStableAcrossReloads() {
        var config = config("blacklist", List.of("jade"), List.of());
        var snapshot = config.getKnownMods();
        var jade = snapshot.get("jade");
        assertNotNull(jade);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> jade.getChannels().clear());
        config.load();
        assertSame(jade, snapshot.get("jade"));
        assertNotSame(jade, config.getKnownMods().get("jade"));
    }
}
