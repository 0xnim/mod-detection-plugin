package xyz.nim.modDetectorPlugin;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** UUID-based exceptions, saved before a command reports success. */
public final class TemporaryExceptions {
    public record Entry(UUID player, String mod, Instant expires) {}
    private final Path file;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public TemporaryExceptions(Path file, Clock clock) throws IOException {
        this.file = file;
        this.clock = clock;
        if (!Files.exists(file)) return;
        var yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
            for (String uuid : yaml.getKeys(false)) {
                var section = yaml.getConfigurationSection(uuid);
                if (section == null) throw new IllegalArgumentException("Invalid exception section: " + uuid);
                for (String mod : section.getKeys(false)) {
                    Entry entry = new Entry(UUID.fromString(uuid), mod, Instant.parse(section.getString(mod, "")));
                    if (entry.expires().isAfter(clock.instant())) entries.put(key(entry.player(), mod), entry);
                }
            }
        } catch (Exception e) {
            throw new IOException("Cannot load temporary exceptions: " + e.getMessage(), e);
        }
    }

    private static String key(UUID player, String mod) { return player + "/" + mod.toLowerCase(Locale.ROOT); }

    public boolean allows(UUID player, String mod) {
        if (mod == null) return false;
        Entry entry = entries.get(key(player, mod));
        return entry != null && entry.expires().isAfter(clock.instant());
    }

    public List<Entry> list() {
        return entries.values().stream().filter(e -> e.expires().isAfter(clock.instant()))
                .sorted(Comparator.comparing(e -> key(e.player(), e.mod()))).toList();
    }

    public void grant(UUID player, String mod, long minutes) throws IOException {
        if (minutes < 1 || minutes > 525600) throw new IllegalArgumentException("Minutes must be between 1 and 525600.");
        // YAML paths use dots as separators. Restrict exception IDs to a single safe path segment.
        if (!mod.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Exception mod IDs must use letters, digits, '_' or '-'.");
        String normalized = mod.toLowerCase(Locale.ROOT);
        var updated = new LinkedHashMap<>(entries);
        updated.put(key(player, normalized), new Entry(player, normalized, clock.instant().plusSeconds(minutes * 60)));
        save(updated);
    }

    public void remove(UUID player, String mod) throws IOException {
        var updated = new LinkedHashMap<>(entries);
        updated.remove(key(player, mod));
        save(updated);
    }

    public Set<UUID> expire() {
        Set<UUID> affected = new HashSet<>();
        entries.values().removeIf(entry -> {
            if (entry.expires().isAfter(clock.instant())) return false;
            affected.add(entry.player());
            return true;
        });
        return affected;
    }

    private void save(Map<String, Entry> updated) throws IOException {
        var yaml = new YamlConfiguration();
        updated.values().forEach(e -> yaml.set(e.player() + "." + e.mod(), e.expires().toString()));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        yaml.save(temp.toFile());
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        entries.clear();
        entries.putAll(updated);
    }
}
