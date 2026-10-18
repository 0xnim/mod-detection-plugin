package xyz.nim.modDetectorPlugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Locale;
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
    private int maxSessionsPerPlayer;
    private record Rule(String glob, Pattern pattern, String modId, String name) {}
    private final List<Rule> identificationRules = new ArrayList<>();
    private final List<Rule> filterRules = new ArrayList<>();
    private static final Comparator<Rule> RULE_ORDER = Comparator
            .comparing((Rule r) -> r.glob().contains("*") || r.glob().contains("?"))
            .thenComparing(Comparator.comparingInt((Rule r) -> r.glob().replace("*", "").replace("?", "").length()).reversed())
            .thenComparing(Rule::glob).thenComparing(Rule::modId);

    private final Map<String, ModDefinition> knownMods = new LinkedHashMap<>();
    private final Map<String, ModDefinition> customMods = new LinkedHashMap<>();

    private final ModDetectorPlugin plugin;

    public ModFilterConfig(ModDetectorPlugin plugin) {
        this.plugin = plugin;
        this.patterns = new ArrayList<>();
    }

    public void load() {
        loadKnownMods();

        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        String modeStr = config.getString("mode", "blacklist").toUpperCase(Locale.ROOT);
        this.mode = modeStr.equals("WHITELIST") ? Mode.WHITELIST : Mode.BLACKLIST;

        this.kick = config.getBoolean("kick", true);

        this.kickMessageFormat = config.getString("kick-message",
                "<red>You have been kicked for using disallowed client mods:</red><newline><yellow><mods></yellow>");
        this.logFormat = config.getString("log-format", "[ModDetector] Player %player% sent plugin message on channel: %channel%");
        this.debug = config.getBoolean("debug", false);
        this.notifyAdmins = config.getBoolean("notify-admins", true);
        this.trackDetections = config.getBoolean("track-detections", true);
        this.logAllChannels = config.getBoolean("log-all-channels", false);

        this.maxSessionsPerPlayer = Math.max(0, config.getInt("history.max-sessions-per-player", 100));
        loadCustomMods(config);

        identificationRules.clear();
        filterRules.clear();
        this.patterns.clear();

        // Build patterns for ALL known mods (for name resolution)
        for (ModDefinition mod : knownMods.values()) {
            for (String channel : mod.getChannels()) {
                Pattern pattern = wildcardToRegex(channel);
                identificationRules.add(new Rule(channel, pattern, mod.getId(), mod.getName()));
            }
        }
        for (ModDefinition mod : customMods.values()) {
            for (String channel : mod.getChannels()) {
                Pattern pattern = wildcardToRegex(channel);
                identificationRules.add(new Rule(channel, pattern, mod.getId(), mod.getName()));
            }
        }

        List<String> blockedModIds = config.getStringList("blocked-mods");
        for (String modId : blockedModIds) {
            String modIdLower = modId.toLowerCase(Locale.ROOT);
            // Check both known mods and custom mods
            ModDefinition mod = knownMods.get(modIdLower);
            if (mod == null) {
                mod = customMods.get(modIdLower);
            }

            if (mod != null) {
                for (String channel : mod.getChannels()) {
                    Pattern pattern = wildcardToRegex(channel);
                    patterns.add(pattern);
                    filterRules.add(new Rule(channel, pattern, mod.getId(), mod.getName()));
                }
                plugin.getLogger().info("Loaded mod: " + mod.getName() + " (" + mod.getChannels().size() + " channels)");
            } else {
                plugin.getLogger().warning("Unknown mod ID in config: " + modId);
            }
        }

        List<String> customPatterns = config.getStringList("custom-patterns");
        for (String patternStr : customPatterns) {
            Pattern pattern = wildcardToRegex(patternStr);
            patterns.add(pattern);
            filterRules.add(new Rule(patternStr, pattern, "custom-patterns", patternStr));
        }

        identificationRules.sort(RULE_ORDER);
        filterRules.sort(RULE_ORDER);
        plugin.getLogger().info("Loaded " + patterns.size() + " channel patterns in " + mode + " mode");
    }

    private void loadCustomMods(FileConfiguration config) {
        customMods.clear();

        ConfigurationSection customModsSection = config.getConfigurationSection("custom-mods");
        if (customModsSection == null) {
            return;
        }

        for (String modId : customModsSection.getKeys(false)) {
            ConfigurationSection modSection = customModsSection.getConfigurationSection(modId);
            if (modSection == null) continue;

            String name = modSection.getString("name", modId);
            String description = modSection.getString("description", "Custom mod");
            List<String> channels = modSection.getStringList("channels");

            if (channels.isEmpty()) {
                plugin.getLogger().warning("Custom mod '" + modId + "' has no channels defined, skipping");
                continue;
            }

            customMods.put(modId.toLowerCase(Locale.ROOT), new ModDefinition(modId, name, description, channels));
        }

        if (!customMods.isEmpty()) {
            plugin.getLogger().info("Loaded " + customMods.size() + " custom mod definitions");
        }
    }

    private void loadKnownMods() {
        knownMods.clear();

        try (InputStream stream = plugin.getResource("mods.yml")) {
            if (stream == null) {
                plugin.getLogger().warning("Could not find mods.yml resource");
                return;
            }

            YamlConfiguration modsConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
            ConfigurationSection modsSection = modsConfig.getConfigurationSection("mods");

            if (modsSection == null) {
                plugin.getLogger().warning("No 'mods' section found in mods.yml");
                return;
            }

            for (String modId : modsSection.getKeys(false)) {
                ConfigurationSection modSection = modsSection.getConfigurationSection(modId);
                if (modSection == null) continue;

                String name = modSection.getString("name", modId);
                String description = modSection.getString("description", "");
                List<String> channels = modSection.getStringList("channels");

                knownMods.put(modId.toLowerCase(Locale.ROOT), new ModDefinition(modId, name, description, channels));
            }

            plugin.getLogger().info("Loaded " + knownMods.size() + " known mod definitions");

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load mods.yml: " + e.getMessage());
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

    public boolean matchesPattern(String channel) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(channel).matches()) {
                return true;
            }
        }
        return false;
    }

    public String getModName(String channel) {
        for (Rule rule : identificationRules) {
            if (rule.pattern().matcher(channel).matches()) return rule.name();
        }
        return channel;
    }

    public String explain(String channel) {
        Rule matched = filterRules.stream().filter(r -> r.pattern().matcher(channel).matches()).findFirst().orElse(null);
        String reason = matched == null ? "no configured rule matched"
                : "rule " + matched.modId() + " (" + matched.glob() + ")";
        return channel + " -> " + getModName(channel) + ": "
                + (shouldBlock(channel) ? "BLOCKED" : "ALLOWED") + " in " + mode + "; " + reason;
    }

    public int getMaxSessionsPerPlayer() {
        return maxSessionsPerPlayer;
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
