package xyz.nim.modDetectorPlugin;

import org.bukkit.entity.Player;
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
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.Map;
import java.util.regex.Pattern;

public class ModFilterConfig {

    public enum Action { LOG, ALERT, KICK }
    private final Map<String, Action> actions = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

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
            this.channels = List.copyOf(channels);
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
    private final Set<String> whitelistedPlayers = new HashSet<>();
    private boolean whitelistOperators;
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

    // Folia regions can read configuration concurrently with a reload.
    public synchronized void load() {
        warnings.clear();
        actions.clear();
        loadKnownMods();

        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        String modeStr = config.getString("mode", "blacklist").toUpperCase(Locale.ROOT);
        if (!modeStr.equals("WHITELIST") && !modeStr.equals("BLACKLIST")) {
            warnings.add("Invalid mode: " + modeStr + "; using blacklist.");
        }
        this.mode = modeStr.equals("WHITELIST") ? Mode.WHITELIST : Mode.BLACKLIST;

        this.kick = config.getBoolean("kick", true);

        this.kickMessageFormat = config.getString("kick-message",
                "<red>You have been kicked for using disallowed client mods:</red><newline><yellow><mods></yellow>");
        this.logFormat = config.getString("log-format", "[ModDetector] Player %player% sent plugin message on channel: %channel%");
        this.debug = config.getBoolean("debug", false);
        this.notifyAdmins = config.getBoolean("notify-admins", true);
        this.trackDetections = config.getBoolean("track-detections", true);
        this.logAllChannels = config.getBoolean("log-all-channels", false);

        this.whitelistOperators = config.getBoolean("whitelist-operators", false);
        loadWhitelistedPlayers(config);

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
                warnings.add("Unknown mod ID in blocked-mods: " + modId);
            }
        }

        List<String> customPatterns = config.getStringList("custom-patterns");
        for (String patternStr : customPatterns) {
            Pattern pattern = wildcardToRegex(patternStr);
            patterns.add(pattern);
            filterRules.add(new Rule(patternStr, pattern, "custom-patterns", patternStr));
        }

        ConfigurationSection actionSection = config.getConfigurationSection("mod-actions");
        if (actionSection != null) {
            for (String id : actionSection.getKeys(false)) {
                String key = id.toLowerCase(Locale.ROOT);
                if (!knownMods.containsKey(key) && !customMods.containsKey(key)) {
                    warnings.add("Unknown mod ID in mod-actions: " + id);
                    continue;
                }
                try {
                    actions.put(key, Action.valueOf(actionSection.getString(id, "").toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    warnings.add("Invalid action for " + id + ": use log, alert, or kick.");
                }
            }
        }
        java.util.Set<String> seenPatterns = new java.util.HashSet<>();
        for (Rule rule : filterRules) {
            if (rule.glob().isBlank()) warnings.add("Empty channel pattern in " + rule.modId());
            if (!seenPatterns.add(rule.glob().toLowerCase(Locale.ROOT))) {
                warnings.add("Duplicate filter pattern: " + rule.glob());
            }
        }
        Map<String, String> owners = new LinkedHashMap<>();
        for (Rule rule : identificationRules) {
            String previous = owners.putIfAbsent(rule.glob().toLowerCase(Locale.ROOT), rule.modId());
            if (previous != null && !previous.equals(rule.modId())) {
                warnings.add("Shared identification pattern " + rule.glob() + ": " + previous + ", " + rule.modId());
            }
        }
        for (String id : customMods.keySet()) {
            if (knownMods.containsKey(id)) warnings.add("Custom mod ID conflicts with built-in ID: " + id);
        }
        if (config.getInt("history.max-sessions-per-player", 100) < 0) {
            warnings.add("Negative history retention becomes unlimited (0).");
        }
        warnings.forEach(plugin.getLogger()::warning);
        identificationRules.sort(RULE_ORDER);
        filterRules.sort(RULE_ORDER);
        plugin.getLogger().info("Loaded " + patterns.size() + " channel patterns in " + mode + " mode");
    }

    private void loadWhitelistedPlayers(FileConfiguration config) {
        whitelistedPlayers.clear();

        for (String value : config.getStringList("whitelisted-players")) {
            String normalized = normalizePlayerIdentifier(value);
            if (!normalized.isEmpty()) {
                whitelistedPlayers.add(normalized);
            }
        }

        if (!whitelistedPlayers.isEmpty()) {
            plugin.getLogger().info("Loaded " + whitelistedPlayers.size() + " whitelisted players");
        }
    }

    private String normalizePlayerIdentifier(String value) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        try {
            return UUID.fromString(trimmed).toString().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }

    public synchronized boolean isPlayerWhitelisted(Player player) {
        String uuid = player.getUniqueId().toString().toLowerCase(Locale.ROOT);
        String name = player.getName().toLowerCase(Locale.ROOT);

        return (whitelistOperators && player.isOp()) || whitelistedPlayers.contains(uuid) || whitelistedPlayers.contains(name);
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
                warnings.add("Custom mod '" + modId + "' has no channels defined, skipping");
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

    static Pattern wildcardToRegex(String wildcard) {
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

    public synchronized List<String> getWarnings() { return List.copyOf(warnings); }

    public synchronized String getModId(String channel) {
        return identificationRules.stream().filter(r -> r.pattern().matcher(channel).matches())
                .map(Rule::modId).map(id -> id.toLowerCase(Locale.ROOT)).findFirst().orElse(null);
    }

    public synchronized boolean hasMod(String id) {
        return knownMods.containsKey(id.toLowerCase(Locale.ROOT)) || customMods.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public synchronized Action getAction(String channel) {
        return actions.getOrDefault(getModId(channel), Action.KICK);
    }

    public synchronized boolean shouldKick(String channel) {
        return shouldBlock(channel) && kick && getAction(channel) == Action.KICK;
    }

    public synchronized boolean shouldAlert(String channel) {
        return shouldBlock(channel) && notifyAdmins && getAction(channel) != Action.LOG;
    }

    // Preview adding one pattern to the existing allow/block list without changing configuration.
    public synchronized boolean previewBlocked(String channel, String pattern) {
        boolean matched = matchesPattern(channel) || wildcardToRegex(pattern).matcher(channel).matches();
        return mode == Mode.BLACKLIST ? matched : !matched;
    }

    public synchronized boolean matchesPattern(String channel) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(channel).matches()) {
                return true;
            }
        }
        return false;
    }

    public synchronized String getModName(String channel) {
        for (Rule rule : identificationRules) {
            if (rule.pattern().matcher(channel).matches()) return rule.name();
        }
        return channel;
    }

    public synchronized String explain(String channel) {
        Rule matched = filterRules.stream().filter(r -> r.pattern().matcher(channel).matches()).findFirst().orElse(null);
        String reason = matched == null ? "no configured rule matched"
                : "rule " + matched.modId() + " (" + matched.glob() + ")";
        return channel + " -> " + getModName(channel) + ": "
                + (shouldBlock(channel) ? "BLOCKED" : "ALLOWED") + " in " + mode + "; " + reason
                + "; action=" + getAction(channel) + "; effective kick=" + shouldKick(channel);
    }

    public synchronized int getMaxSessionsPerPlayer() {
        return maxSessionsPerPlayer;
    }

    public synchronized boolean shouldBlock(String channel) {
        boolean matches = matchesPattern(channel);
        return switch (mode) {
            case BLACKLIST -> matches;
            case WHITELIST -> !matches;
        };
    }

    public synchronized Map<String, ModDefinition> getKnownMods() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(knownMods));
    }

    public synchronized Map<String, ModDefinition> getCustomMods() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(customMods));
    }

    public synchronized Mode getMode() {
        return mode;
    }

    public synchronized boolean isKick() {
        return kick;
    }

    public synchronized String getKickMessageFormat() {
        return kickMessageFormat;
    }

    public synchronized String getLogFormat() {
        return logFormat;
    }

    public synchronized boolean isDebug() {
        return debug;
    }

    public synchronized boolean isNotifyAdmins() {
        return notifyAdmins;
    }

    public synchronized boolean isTrackDetections() {
        return trackDetections;
    }

    public synchronized boolean isLogAllChannels() {
        return logAllChannels;
    }

    public synchronized String formatLogMessage(String playerName, String channel) {
        return logFormat
                .replace("%player%", playerName)
                .replace("%channel%", channel);
    }
}
