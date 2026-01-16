package xyz.nim.modDetectorPlugin;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ModFilterConfig {

    public enum Mode {
        WHITELIST,
        BLACKLIST
    }

    public static class ModDefinition {
        private final String id;
        private final String name;
        private final String description;
        private final List<String> channels;

        public ModDefinition(String id, String name, String description, List<String> channels) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.channels = channels;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public List<String> getChannels() { return channels; }
    }

    private Mode mode;
    private boolean kick;
    private String kickMessageFormat;
    private String logFormat;
    private List<Pattern> patterns;
    private boolean debug;
    private boolean notifyAdmins;
    private boolean trackDetections;
    private boolean logAllChannels;

    private final Map<String, ModDefinition> knownMods = new HashMap<>();
    private final Map<String, ModDefinition> customMods = new HashMap<>();
    private final Map<Pattern, String> patternToModName = new HashMap<>();
    private final Map<Pattern, String> allModPatterns = new HashMap<>();

    private final ModDetectorPlugin plugin;
    private final Yaml yaml = new Yaml();

    public ModFilterConfig(ModDetectorPlugin plugin) {
        this.plugin = plugin;
        this.patterns = new ArrayList<>();
    }

    public void load() {
        loadKnownMods();

        saveDefaultConfig();
        Map<String, Object> config = loadConfigFile();

        String modeStr = getString(config, "mode", "blacklist").toUpperCase();
        this.mode = modeStr.equals("WHITELIST") ? Mode.WHITELIST : Mode.BLACKLIST;

        this.kick = getBoolean(config, "kick", true);

        this.kickMessageFormat = getString(config, "kick-message",
                "<red>You have been kicked for using disallowed client mods:</red><newline><yellow><mods></yellow>");
        this.logFormat = getString(config, "log-format", "[ModDetector] Player %player% sent plugin message on channel: %channel%");
        this.debug = getBoolean(config, "debug", false);
        this.notifyAdmins = getBoolean(config, "notify-admins", true);
        this.trackDetections = getBoolean(config, "track-detections", true);
        this.logAllChannels = getBoolean(config, "log-all-channels", false);

        loadCustomMods(config);

        this.patterns.clear();
        this.patternToModName.clear();
        this.allModPatterns.clear();

        // Build patterns for ALL known mods (for name resolution)
        for (ModDefinition mod : knownMods.values()) {
            for (String channel : mod.getChannels()) {
                Pattern pattern = wildcardToRegex(channel);
                allModPatterns.put(pattern, mod.getName());
            }
        }
        for (ModDefinition mod : customMods.values()) {
            for (String channel : mod.getChannels()) {
                Pattern pattern = wildcardToRegex(channel);
                allModPatterns.put(pattern, mod.getName());
            }
        }

        List<String> blockedModIds = getStringList(config, "blocked-mods");
        for (String modId : blockedModIds) {
            String modIdLower = modId.toLowerCase();
            ModDefinition mod = knownMods.get(modIdLower);
            if (mod == null) {
                mod = customMods.get(modIdLower);
            }

            if (mod != null) {
                for (String channel : mod.getChannels()) {
                    Pattern pattern = wildcardToRegex(channel);
                    patterns.add(pattern);
                    patternToModName.put(pattern, mod.getName());
                }
                plugin.getLogger().info("Loaded mod: " + mod.getName() + " (" + mod.getChannels().size() + " channels)");
            } else {
                plugin.getLogger().warn("Unknown mod ID in config: " + modId);
            }
        }

        List<String> customPatterns = getStringList(config, "custom-patterns");
        for (String patternStr : customPatterns) {
            Pattern pattern = wildcardToRegex(patternStr);
            patterns.add(pattern);
            patternToModName.put(pattern, patternStr);
        }

        plugin.getLogger().info("Loaded " + patterns.size() + " channel patterns in " + mode + " mode");
    }

    private void saveDefaultConfig() {
        Path configPath = plugin.getDataDirectory().resolve("config.yml");
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(plugin.getDataDirectory());
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (stream != null) {
                        Files.copy(stream, configPath);
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().warn("Failed to save default config: " + e.getMessage());
            }
        }
    }

    private Map<String, Object> loadConfigFile() {
        Path configPath = plugin.getDataDirectory().resolve("config.yml");
        try (InputStream stream = Files.newInputStream(configPath)) {
            Map<String, Object> config = yaml.load(stream);
            return config != null ? config : new HashMap<>();
        } catch (IOException e) {
            plugin.getLogger().warn("Failed to load config.yml: " + e.getMessage());
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadCustomMods(Map<String, Object> config) {
        customMods.clear();

        Object customModsObj = config.get("custom-mods");
        if (!(customModsObj instanceof Map)) {
            return;
        }

        Map<String, Object> customModsSection = (Map<String, Object>) customModsObj;

        for (String modId : customModsSection.keySet()) {
            Object modObj = customModsSection.get(modId);
            if (!(modObj instanceof Map)) continue;

            Map<String, Object> modSection = (Map<String, Object>) modObj;
            String name = getString(modSection, "name", modId);
            String description = getString(modSection, "description", "Custom mod");
            List<String> channels = getStringList(modSection, "channels");

            if (channels.isEmpty()) {
                plugin.getLogger().warn("Custom mod '" + modId + "' has no channels defined, skipping");
                continue;
            }

            customMods.put(modId.toLowerCase(), new ModDefinition(modId, name, description, channels));
        }

        if (!customMods.isEmpty()) {
            plugin.getLogger().info("Loaded " + customMods.size() + " custom mod definitions");
        }
    }

    @SuppressWarnings("unchecked")
    private void loadKnownMods() {
        knownMods.clear();

        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("mods.yml")) {
            if (stream == null) {
                plugin.getLogger().warn("Could not find mods.yml resource");
                return;
            }

            Map<String, Object> modsConfig = yaml.load(stream);
            Object modsObj = modsConfig.get("mods");

            if (!(modsObj instanceof Map)) {
                plugin.getLogger().warn("No 'mods' section found in mods.yml");
                return;
            }

            Map<String, Object> modsSection = (Map<String, Object>) modsObj;

            for (String modId : modsSection.keySet()) {
                Object modObj = modsSection.get(modId);
                if (!(modObj instanceof Map)) continue;

                Map<String, Object> modSection = (Map<String, Object>) modObj;
                String name = getString(modSection, "name", modId);
                String description = getString(modSection, "description", "");
                List<String> channels = getStringList(modSection, "channels");

                knownMods.put(modId.toLowerCase(), new ModDefinition(modId, name, description, channels));
            }

            plugin.getLogger().info("Loaded " + knownMods.size() + " known mod definitions");

        } catch (Exception e) {
            plugin.getLogger().error("Failed to load mods.yml: " + e.getMessage());
        }
    }

    private Pattern wildcardToRegex(String wildcard) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : wildcard.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                case '.' -> regex.append("\\.");
                case '\\' -> regex.append("\\\\");
                case '^' -> regex.append("\\^");
                case '$' -> regex.append("\\$");
                case '|' -> regex.append("\\|");
                case '[' -> regex.append("\\[");
                case ']' -> regex.append("\\]");
                case '(' -> regex.append("\\(");
                case ')' -> regex.append("\\)");
                case '{' -> regex.append("\\{");
                case '}' -> regex.append("\\}");
                case '+' -> regex.append("\\+");
                default -> regex.append(c);
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    // Helper methods for YAML parsing
    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(item.toString());
            }
            return result;
        }
        return new ArrayList<>();
    }

    public boolean matchesPattern(String channel) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(channel).matches()) {
                return true;
            }
        }
        return false;
    }

    public String getModName(String channel) {
        // Check all known mods (not just blocked ones) for name resolution
        for (Map.Entry<Pattern, String> entry : allModPatterns.entrySet()) {
            if (entry.getKey().matcher(channel).matches()) {
                return entry.getValue();
            }
        }
        return channel;
    }

    public boolean shouldBlock(String channel) {
        boolean matches = matchesPattern(channel);
        return switch (mode) {
            case BLACKLIST -> matches;
            case WHITELIST -> !matches;
        };
    }

    public Map<String, ModDefinition> getKnownMods() {
        return knownMods;
    }

    public Map<String, ModDefinition> getCustomMods() {
        return customMods;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isKick() {
        return kick;
    }

    public String getKickMessageFormat() {
        return kickMessageFormat;
    }

    public String getLogFormat() {
        return logFormat;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isNotifyAdmins() {
        return notifyAdmins;
    }

    public boolean isTrackDetections() {
        return trackDetections;
    }

    public boolean isLogAllChannels() {
        return logAllChannels;
    }

    public String formatLogMessage(String playerName, String channel) {
        return logFormat
                .replace("%player%", playerName)
                .replace("%channel%", channel);
    }
}
