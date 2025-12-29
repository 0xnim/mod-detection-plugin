package xyz.nim.modDetectorPlugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
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

    public enum Action {
        KICK,
        LOG,
        BOTH
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
    private Action action;
    private String kickMessageFormat;
    private String logFormat;
    private List<Pattern> patterns;
    private boolean debug;
    private boolean notifyAdmins;

    private final Map<String, ModDefinition> knownMods = new HashMap<>();
    private final Map<Pattern, String> patternToModName = new HashMap<>();

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

        String modeStr = config.getString("mode", "blacklist").toUpperCase();
        this.mode = modeStr.equals("WHITELIST") ? Mode.WHITELIST : Mode.BLACKLIST;

        String actionStr = config.getString("action", "both").toUpperCase();
        this.action = switch (actionStr) {
            case "KICK" -> Action.KICK;
            case "LOG" -> Action.LOG;
            default -> Action.BOTH;
        };

        this.kickMessageFormat = config.getString("kick-message",
                "<red>You have been kicked for using disallowed client mods:</red><newline><yellow><mods></yellow>");
        this.logFormat = config.getString("log-format", "[ModDetector] Player %player% sent plugin message on channel: %channel%");
        this.debug = config.getBoolean("debug", false);
        this.notifyAdmins = config.getBoolean("notify-admins", true);

        this.patterns.clear();
        this.patternToModName.clear();

        List<String> blockedModIds = config.getStringList("blocked-mods");
        for (String modId : blockedModIds) {
            ModDefinition mod = knownMods.get(modId.toLowerCase());
            if (mod != null) {
                for (String channel : mod.getChannels()) {
                    Pattern pattern = wildcardToRegex(channel);
                    patterns.add(pattern);
                    patternToModName.put(pattern, mod.getName());
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
            patternToModName.put(pattern, patternStr);
        }

        plugin.getLogger().info("Loaded " + patterns.size() + " channel patterns in " + mode + " mode");
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

                knownMods.put(modId.toLowerCase(), new ModDefinition(modId, name, description, channels));
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
        for (Map.Entry<Pattern, String> entry : patternToModName.entrySet()) {
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

    public Mode getMode() {
        return mode;
    }

    public Action getAction() {
        return action;
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

    public String formatLogMessage(String playerName, String channel) {
        return logFormat
                .replace("%player%", playerName)
                .replace("%channel%", channel);
    }
}
