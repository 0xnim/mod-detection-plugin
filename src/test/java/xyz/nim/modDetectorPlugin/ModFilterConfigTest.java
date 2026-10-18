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
}
