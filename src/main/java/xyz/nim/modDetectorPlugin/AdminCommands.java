package xyz.nim.modDetectorPlugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Administrative queries use stored UUIDs and never trigger an external player lookup. */
@SuppressWarnings("UnstableApiUsage")
final class AdminCommands {
    private static final int PAGE_SIZE = 8;
    private static final Map<String, String> USAGE = new LinkedHashMap<>();
    static {
        USAGE.put("reload", "reload");
        USAGE.put("status", "status");
        USAGE.put("mods", "mods [page]");
        USAGE.put("players", "players [page]");
        USAGE.put("info", "info <name|uuid> [page]");
        USAGE.put("changes", "changes <name|uuid> [page]");
        USAGE.put("explain", "explain <online-player> [page]");
        USAGE.put("preview", "preview <new-channel-pattern> [page]");
        USAGE.put("validate", "validate [page]");
        USAGE.put("search", "search <mod|channel|date> <value> [page] (use quotes around mod names)");
        USAGE.put("discovered", "discovered [page]");
        USAGE.put("exception", "exception <add <name|uuid> <mod-id> <minutes>|remove <name|uuid> <mod-id>|list [page]>");
        USAGE.put("debug", "debug");
    }
    private final ModDetectorPlugin plugin;
    private final ModFilterConfig config;
    private final DetectionLogger logger;
    private final ModMessageListener listener;

    AdminCommands(ModDetectorPlugin plugin, ModFilterConfig config, DetectionLogger logger, ModMessageListener listener) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.listener = listener;
    }

    void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var root = Commands.literal("moddetector")
                    .requires(source -> source.getSender().hasPermission("moddetector.admin"))
                    .executes(ctx -> help(ctx.getSource().getSender()));
            for (String command : USAGE.keySet()) {
                root.then(Commands.literal(command)
                        .executes(ctx -> execute(ctx.getSource().getSender(), command, ""))
                        .then(Commands.argument("arguments", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx.getSource().getSender(), command,
                                        StringArgumentType.getString(ctx, "arguments")))));
            }
            event.registrar().register(root.build(), "ModDetector admin commands", List.of("md"));
        });
    }

    private int help(CommandSender sender) {
        USAGE.values().forEach(usage -> say(sender, "/md " + usage));
        return Command.SINGLE_SUCCESS;
    }

    int execute(CommandSender sender, String command, String input) {
        if (!sender.hasPermission("moddetector.admin")) {
            say(sender, "You do not have permission to use this command.");
            return 0;
        }
        try {
            List<String> args = arguments(input);
            switch (command) {
                case "reload" -> {
                    count(args, 0, 0);
                    config.load();
                    logger.applyRetention();
                    say(sender, "Configuration reloaded. Warnings: " + config.getWarnings().size() + ". Use /md validate.");
                }
                case "status" -> {
                    count(args, 0, 0);
                    say(sender, "Mode: " + config.getMode() + "; kicks: " + config.isKick()
                            + "; alerts: " + config.isNotifyAdmins() + "; session logging: " + config.isTrackDetections()
                            + "; channel discovery: " + config.isLogAllChannels());
                }
                case "debug" -> { count(args, 0, 0); say(sender, "Debug logging: " + config.isDebug()); }
                case "mods" -> {
                    count(args, 0, 1);
                    List<String> rows = new ArrayList<>();
                    config.getKnownMods().values().forEach(m -> rows.add(m.getId() + " -> " + m.getName()));
                    config.getCustomMods().values().forEach(m -> rows.add(m.getId() + " -> " + m.getName() + " (custom)"));
                    Collections.sort(rows);
                    page(sender, "Mod definitions", rows, pageArg(args, 0));
                }
                case "players" -> {
                    count(args, 0, 1);
                    List<String> rows = new ArrayList<>();
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        Set<String> channels = listener.getAllRegisteredChannels().getOrDefault(player.getUniqueId(), Set.of());
                        if (!channels.isEmpty()) rows.add(player.getName() + ": " + sorted(channels));
                    }
                    Collections.sort(rows);
                    page(sender, "Online players with registered channels", rows, pageArg(args, 0),
                            row -> Component.text("[ModDetector] " + row)
                                    .clickEvent(ClickEvent.runCommand("/md info " + row.substring(0, row.indexOf(':'))))
                                    .hoverEvent(HoverEvent.showText(Component.text("Click to view channels"))));
                }
                case "info" -> info(sender, args);
                case "changes" -> changes(sender, args);
                case "explain" -> explain(sender, args);
                case "preview" -> preview(sender, args);
                case "validate" -> {
                    count(args, 0, 1);
                    page(sender, "Loaded configuration warnings (reload after editing)", config.getWarnings(), pageArg(args, 0));
                }
                case "search" -> search(sender, args);
                case "discovered" -> discovered(sender, args);
                case "exception" -> exception(sender, args);
                default -> { return help(sender); }
            }
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException e) {
            say(sender, e.getMessage() + " Usage: /md " + USAGE.get(command));
            return 0;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save temporary exception: " + e.getMessage());
            say(sender, "Could not save the exception. No exception change was applied.");
            return 0;
        }
    }

    private void info(CommandSender sender, List<String> args) {
        count(args, 1, 2);
        UUID uuid = resolve(args.getFirst());
        Player online = Bukkit.getPlayer(uuid);
        var data = logger.getPlayerData(uuid);
        List<String> rows = new ArrayList<>();
        rows.add("UUID: " + uuid + "; online: " + (online != null));
        if (online != null) {
            for (String channel : new TreeSet<>(listener.getAllRegisteredChannels().getOrDefault(uuid, Set.of()))) {
                rows.add("Current: " + config.explain(channel));
            }
        }
        if (data != null) {
            rows.add("First seen: " + data.firstSeen + "; last seen: " + data.lastSeen);
            rows.add("Sessions: " + data.sessionCount + "; retained: " + data.sessions.size()
                    + "; playtime: " + data.totalTimePlayedSeconds + "s");
            rows.add("Previously detected mods: " + sorted(data.mods));
            rows.add("Previously unknown channels: " + sorted(data.channels));
            for (int i = data.sessions.size() - 1; i >= 0; i--) {
                var session = data.sessions.get(i);
                rows.add("Session #" + (data.sessionCount - data.sessions.size() + i + 1) + ": "
                        + session.joinTime + " -> " + session.leaveTime + " (" + session.durationSeconds + "s); mods: "
                        + sorted(DetectionLogger.getModsForSession(data.sessions, i)) + "; channels: "
                        + (session.registeredChannels == null ? "unavailable in legacy record" : sorted(session.registeredChannels)));
            }
        } else rows.add("No saved history.");
        page(sender, "Player " + (online != null ? online.getName() : data.username), rows, pageArg(args, 1));
    }

    private void changes(CommandSender sender, List<String> args) {
        count(args, 1, 2);
        UUID uuid = resolve(args.getFirst());
        var data = logger.getPlayerData(uuid);
        if (data == null || data.sessions.isEmpty()) throw new IllegalArgumentException("No completed session is available for comparison.");
        int last = data.sessions.size() - 1;
        Set<String> beforeMods;
        Set<String> afterMods;
        Set<String> beforeChannels;
        Set<String> afterChannels;
        String title;
        if (Bukkit.getPlayer(uuid) != null) {
            beforeMods = DetectionLogger.getModsForSession(data.sessions, last);
            beforeChannels = data.sessions.get(last).registeredChannels;
            afterChannels = listener.getAllRegisteredChannels().getOrDefault(uuid, Set.of());
            afterMods = new TreeSet<>();
            for (String channel : afterChannels) {
                if (config.getModId(channel) != null) afterMods.add(config.getModName(channel));
            }
            title = "Previous completed session -> current observations";
        } else {
            if (last < 1) throw new IllegalArgumentException("Two retained sessions are required for an offline comparison.");
            beforeMods = DetectionLogger.getModsForSession(data.sessions, last - 1);
            afterMods = DetectionLogger.getModsForSession(data.sessions, last);
            beforeChannels = data.sessions.get(last - 1).registeredChannels;
            afterChannels = data.sessions.get(last).registeredChannels;
            title = "Last two completed sessions";
        }
        List<String> rows = new ArrayList<>();
        differences(rows, "Mod", beforeMods, afterMods);
        if (beforeChannels == null || afterChannels == null) rows.add("Channel comparison unavailable: a legacy session has no complete snapshot.");
        else differences(rows, "Channel", beforeChannels, afterChannels);
        if (rows.isEmpty()) rows.add("No observed changes.");
        page(sender, title, rows, pageArg(args, 1));
    }

    static void differences(List<String> rows, String kind, Set<String> before, Set<String> after) {
        new TreeSet<>(after).stream().filter(value -> !before.contains(value)).forEach(value -> rows.add(kind + " added: " + value));
        new TreeSet<>(before).stream().filter(value -> !after.contains(value)).forEach(value -> rows.add(kind + " removed: " + value));
    }

    private void explain(CommandSender sender, List<String> args) {
        count(args, 1, 2);
        Player player = Bukkit.getPlayer(resolve(args.getFirst()));
        if (player == null) throw new IllegalArgumentException("The player must be online.");
        int requestedPage = pageArg(args, 1);
        player.getScheduler().run(plugin, task -> {
            List<String> rows = new ArrayList<>();
            rows.add("Kicks enabled: " + config.isKick() + "; bypass permission: " + player.hasPermission("moddetector.bypass")
                    + "; whitelisted=" + config.isPlayerWhitelisted(player));
            for (String channel : new TreeSet<>(listener.getAllRegisteredChannels().getOrDefault(player.getUniqueId(), Set.of()))) {
                rows.add(config.explain(channel) + "; temporary exception=" + listener.isExcepted(player, channel));
            }
            replyPage(sender, "Rules for " + player.getName(), rows, requestedPage);
        }, null);
    }

    private void preview(CommandSender sender, List<String> args) {
        count(args, 1, 2);
        String pattern = args.getFirst();
        if (pattern.isBlank()) throw new IllegalArgumentException("Pattern cannot be empty.");
        int requestedPage = pageArg(args, 1);
        var pending = new ArrayList<java.util.concurrent.CompletableFuture<List<String>>>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            var result = new java.util.concurrent.CompletableFuture<List<String>>();
            pending.add(result);
            var task = player.getScheduler().run(plugin, ignored -> {
                try {
                    List<String> rows = new ArrayList<>();
                    boolean exempt = player.hasPermission("moddetector.bypass") || config.isPlayerWhitelisted(player);
                    for (String channel : new TreeSet<>(listener.getAllRegisteredChannels().getOrDefault(player.getUniqueId(), Set.of()))) {
                        boolean before = config.shouldBlock(channel);
                        boolean after = config.previewBlocked(channel, pattern);
                        if (before == after) continue;
                        boolean kick = after && config.isKick() && config.getAction(channel) == ModFilterConfig.Action.KICK
                                && !exempt && !listener.isExcepted(player, channel);
                        rows.add(player.getName() + ": " + channel + "; " + (before ? "BLOCKED" : "ALLOWED")
                                + " -> " + (after ? "BLOCKED" : "ALLOWED") + "; would kick=" + kick);
                    }
                    result.complete(rows);
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            }, () -> result.complete(List.of()));
            if (task == null) result.complete(List.of());
        }
        java.util.concurrent.CompletableFuture.allOf(pending.toArray(java.util.concurrent.CompletableFuture[]::new))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("Could not complete rule preview: " + error.getMessage());
                        reply(sender, () -> say(sender, "Could not complete the preview. Please retry."));
                        return;
                    }
                    List<String> rows = new ArrayList<>();
                    pending.forEach(result -> rows.addAll(result.join()));
                    Collections.sort(rows);
                    if (rows.isEmpty()) rows.add("No online channel decisions would change.");
                    replyPage(sender, "Preview adding '" + pattern + "' in " + config.getMode() + " mode; no changes applied", rows, requestedPage);
                });
    }

    private void replyPage(CommandSender sender, String title, List<String> rows, int requestedPage) {
        reply(sender, () -> {
            try { page(sender, title, rows, requestedPage); }
            catch (IllegalArgumentException e) { say(sender, e.getMessage()); }
        });
    }

    private void reply(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) player.getScheduler().run(plugin, task -> action.run(), null);
        else Bukkit.getGlobalRegionScheduler().execute(plugin, action);
    }

    private void search(CommandSender sender, List<String> args) {
        count(args, 2, 3);
        String field = args.get(0).toLowerCase(Locale.ROOT);
        String value = args.get(1);
        if (!Set.of("mod", "channel", "date").contains(field)) throw new IllegalArgumentException("Search field must be mod, channel, or date.");
        if (value.isBlank()) throw new IllegalArgumentException("Search value cannot be empty.");
        if (field.equals("date")) {
            try { LocalDate.parse(value); }
            catch (DateTimeParseException e) { throw new IllegalArgumentException("Date must use YYYY-MM-DD (UTC)."); }
        }
        // Accept a mod ID as well as the stored display name.
        String modName = value;
        var definition = config.getKnownMods().get(value.toLowerCase(Locale.ROOT));
        if (definition == null) definition = config.getCustomMods().get(value.toLowerCase(Locale.ROOT));
        if (definition != null) modName = definition.getName();
        final String needle = modName.toLowerCase(Locale.ROOT);
        List<String> rows = new ArrayList<>();
        for (var data : logger.getPlayers()) {
            boolean match = switch (field) {
                case "mod" -> data.mods.stream().anyMatch(m -> m.toLowerCase(Locale.ROOT).contains(needle));
                case "channel" -> data.channels.stream().anyMatch(c -> contains(c, value))
                        || data.observations.keySet().stream().anyMatch(c -> contains(c, value))
                        || data.sessions.stream().anyMatch(s -> s.registeredChannels != null
                        && s.registeredChannels.stream().anyMatch(c -> contains(c, value)));
                case "date" -> data.sessions.stream().anyMatch(s -> overlapsDate(s, value));
                default -> false;
            };
            if (match) rows.add(data.username + " (" + data.uuid + "); last seen: " + data.lastSeen);
        }
        page(sender, "Saved history: " + field + "=" + value, rows, pageArg(args, 2));
    }

    static boolean overlapsDate(DetectionLogger.SessionRecord session, String date) {
        if (session.leaveTime == null || session.leaveTime.length() < 10) return false;
        String end = session.leaveTime.substring(0, 10);
        String start = session.joinTime == null || session.joinTime.length() < 10 ? end : session.joinTime.substring(0, 10);
        return start.compareTo(date) <= 0 && end.compareTo(date) >= 0;
    }

    private void discovered(CommandSender sender, List<String> args) {
        count(args, 0, 1);
        var players = logger.getPlayers();
        List<String> rows = new ArrayList<>();
        for (String channel : new TreeSet<>(logger.getDiscoveredChannels())) {
            String first = null;
            String last = null;
            int count = 0;
            for (var player : players) {
                var observation = player.observations.get(channel);
                if (observation == null) continue;
                count++;
                if (observation.firstSeen != null && (first == null || observation.firstSeen.compareTo(first) < 0)) first = observation.firstSeen;
                if (observation.lastSeen != null && (last == null || observation.lastSeen.compareTo(last) > 0)) last = observation.lastSeen;
            }
            rows.add(channel + " -> " + config.getModName(channel) + "; first=" + (first == null ? "unknown" : first)
                    + "; last=" + (last == null ? "unknown" : last) + "; players observed since statistics began=" + count);
        }
        page(sender, "Discovered channels (requires log-all-channels)", rows, pageArg(args, 0));
    }

    private void exception(CommandSender sender, List<String> args) throws IOException {
        count(args, 1, 4);
        var exceptions = plugin.getTemporaryExceptions();
        switch (args.getFirst()) {
            case "list" -> {
                count(args, 1, 2);
                page(sender, "Active temporary exceptions", exceptions.list().stream()
                        .map(e -> e.player() + " / " + e.mod() + " until " + e.expires()).toList(), pageArg(args, 1));
            }
            case "add" -> {
                count(args, 4, 4);
                UUID uuid = resolveExceptionPlayer(args.get(1));
                if (!config.hasMod(args.get(2))) throw new IllegalArgumentException("Unknown mod ID: " + args.get(2));
                exceptions.grant(uuid, args.get(2), Long.parseLong(args.get(3)));
                say(sender, "Saved exception for " + uuid + " / " + args.get(2) + " for " + args.get(3) + " minutes.");
            }
            case "remove" -> {
                count(args, 3, 3);
                UUID uuid = resolveExceptionPlayer(args.get(1));
                exceptions.remove(uuid, args.get(2));
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) player.getScheduler().run(plugin, task -> listener.recheck(player), null);
                say(sender, "Removed exception for " + uuid + " / " + args.get(2) + ".");
            }
            default -> throw new IllegalArgumentException("Exception action must be add, remove, or list.");
        }
    }

    private UUID resolveExceptionPlayer(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return resolve(value); }
    }

    private UUID resolve(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (Bukkit.getPlayer(uuid) != null || logger.getPlayerData(uuid) != null) return uuid;
        } catch (IllegalArgumentException ignored) { }
        Player online = Bukkit.getPlayerExact(value);
        if (online != null) return online.getUniqueId();
        List<UUID> matches = logger.getPlayers().stream().filter(d -> value.equalsIgnoreCase(d.username)).map(d -> d.uuid).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Multiple saved players have this name. Use a UUID.");
        if (matches.isEmpty()) throw new IllegalArgumentException("No online player or saved history matches " + value + ".");
        return matches.getFirst();
    }

    private static boolean contains(String text, String value) { return text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)); }
    private static String sorted(Set<String> values) { return values.isEmpty() ? "none" : String.join(", ", new TreeSet<>(values)); }
    private static void say(CommandSender sender, String message) { sender.sendMessage(Component.text("[ModDetector] " + message)); }
    private static void count(List<String> args, int min, int max) {
        if (args.size() < min || args.size() > max) throw new IllegalArgumentException("Incorrect argument count.");
    }
    private static int pageArg(List<String> args, int index) { return args.size() > index ? Integer.parseInt(args.get(index)) : 1; }
    private static void page(CommandSender sender, String title, List<String> rows, int page) {
        page(sender, title, rows, page, row -> Component.text("[ModDetector] " + row));
    }
    private static void page(CommandSender sender, String title, List<String> rows, int page,
                             java.util.function.Function<String, Component> render) {
        int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page < 1 || page > pages) throw new IllegalArgumentException("Page must be between 1 and " + pages + ".");
        say(sender, title + " — page " + page + "/" + pages + " (" + rows.size() + " results)");
        if (rows.isEmpty()) say(sender, "No results.");
        rows.subList((page - 1) * PAGE_SIZE, Math.min(page * PAGE_SIZE, rows.size())).forEach(row -> sender.sendMessage(render.apply(row)));
    }
    static List<String> arguments(String input) {
        var reader = new com.mojang.brigadier.StringReader(input);
        List<String> result = new ArrayList<>();
        try {
            while (reader.canRead()) {
                reader.skipWhitespace();
                if (!reader.canRead()) break;
                // Unquoted channel patterns contain punctuation that Brigadier's readString excludes.
                if (reader.peek() == '"' || reader.peek() == '\'') result.add(reader.readQuotedString());
                else {
                    int start = reader.getCursor();
                    while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
                    result.add(input.substring(start, reader.getCursor()));
                }
                if (reader.canRead() && !Character.isWhitespace(reader.peek())) throw new IllegalArgumentException("Separate arguments with spaces.");
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalArgumentException("Invalid quoted argument: " + e.getMessage());
        }
        return result;
    }
}
