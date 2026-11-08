package com.extantape.stationmaster;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * /stationmaster: console commands for Leaf Juice and scripts. Each takes a request ID and answers with exactly one
 * line, {@code stationmaster-result <request-id> <json>}, which may come a moment later. See README for the fields.
 */
final class ConsoleCommands implements BasicCommand {

    static final String PREFIX = "stationmaster-result ";
    private static final Pattern REQUEST = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern VIA = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Gson GSON = new Gson();
    private static final List<String> SUBS = List.of("profiles", "resolve", "show", "apply", "attempts", "log", "reload");

    private final StationmasterPlugin plugin;

    ConsoleCommands(StationmasterPlugin plugin) { this.plugin = plugin; }

    /** The console, and approvers in the game (who only get reload). Hidden from everyone else. */
    @Override
    public boolean canUse(CommandSender sender) { return isConsole(sender) || sender.hasPermission(ChangeService.PERMISSION); }

    static boolean isConsole(CommandSender sender) { return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender; }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        boolean console = isConsole(sender);
        if (args.length == 0) {
            sender.sendMessage("Stationmaster " + plugin.getPluginMeta().getVersion() + ": " + String.join(", ", SUBS) + ". See the README for details.");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!console && !sender.hasPermission(ChangeService.PERMISSION)) { sender.sendMessage(PlayerCommands.bad("Only approvers can do that.")); return; }
            for (String line : plugin.reload()) sender.sendMessage(line);
            return;
        }
        if (!console) {
            sender.sendMessage(PlayerCommands.bad("Those are console commands. In the game, use /approve <name> or /profile <name>."));
            return;
        }
        if (!SUBS.contains(sub)) { sender.sendMessage("Unknown: " + sub + ". Try " + String.join(", ", SUBS) + "."); return; }
        if (args.length < 2 || !REQUEST.matcher(args[1]).matches()) {
            reply(sender, "-", error("bad_request", "Give a request ID first: 1 to 64 letters, digits, '.', '_', ':' or '-'."));
            return;
        }
        String req = args[1];
        String[] rest = java.util.Arrays.copyOfRange(args, 2, args.length);
        switch (sub) {
            case "profiles" -> reply(sender, req, profiles());
            case "resolve" -> resolve(sender, req, rest);
            case "show" -> show(sender, req, rest);
            case "apply" -> apply(sender, req, rest);
            case "attempts" -> reply(sender, req, attempts());
            case "log" -> log(sender, req, rest);
            default -> { }
        }
    }

    private void reply(CommandSender sender, String req, JsonObject json) {
        plugin.runMain(() -> sender.sendMessage(PREFIX + req + " " + GSON.toJson(json)));
    }

    static JsonObject error(String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("status", "error");
        o.addProperty("code", code);
        o.addProperty("message", message);
        return o;
    }

    private JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("status", "ok");
        return o;
    }

    private JsonObject profiles() {
        Settings s = plugin.settings();
        JsonObject o = s.ok() ? ok() : error("config_broken", s.problems().get(0));
        o.addProperty("luckperms", plugin.store().ready());
        JsonArray list = new JsonArray();
        for (Profile p : s.profiles().values()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", p.id());
            j.addProperty("label", p.label());
            j.addProperty("group", p.group());
            j.addProperty("toggle", p.toggle());
            if (p.circle() != null) j.addProperty("circle", p.circle());
            if (p.confirm() != null) j.addProperty("confirm", p.confirm());
            j.add("worlds", ChangeService.strings(p.worlds()));
            j.add("description", ChangeService.strings(p.description()));
            list.add(j);
        }
        o.add("profiles", list);
        o.add("restricted", ChangeService.strings(s.restricted()));
        o.add("problems", ChangeService.strings(s.problems()));
        return o;
    }

    private void resolve(CommandSender sender, String req, String[] rest) {
        if (rest.length != 1) { reply(sender, req, error("bad_request", "Use: stationmaster resolve <request-id> <name>")); return; }
        plugin.names().resolve(rest[0], plugin.settings().lookup()).whenComplete((who, e) -> {
            if (e != null) { reply(sender, req, nameError(e, rest[0])); return; }
            plugin.runMain(() -> {
                JsonObject o = ok();
                o.addProperty("uuid", who.id().toString());
                o.addProperty("name", who.name());
                var seen = Bukkit.getOfflinePlayer(who.id());
                o.addProperty("whitelisted", seen.isWhitelisted());
                // So Leaf Juice can warn before approving an account that has never come near this server.
                o.addProperty("playedBefore", seen.hasPlayedBefore());
                JoinAttempts.Attempt tried = plugin.attempts().of(who.id());
                o.addProperty("triedToJoin", tried == null ? 0 : tried.attempts);
                if (tried != null) o.addProperty("lastTried", tried.last);
                reply(sender, req, o);
            });
        });
    }

    private JsonObject nameError(Throwable e, String typed) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof NameResolver.NameProblem np) return error(np.code, np.getMessage());
        return error("lookup_failed", "Couldn't look up " + typed + ".");
    }

    private void show(CommandSender sender, String req, String[] rest) {
        if (rest.length != 1) { reply(sender, req, error("bad_request", "Use: stationmaster show <request-id> <uuid|name>")); return; }
        if (!plugin.store().ready()) { reply(sender, req, error("luckperms_missing", "LuckPerms isn't running.")); return; }
        UUID id = parseUuid(rest[0]);
        var who = id != null ? java.util.concurrent.CompletableFuture.completedFuture(new NameResolver.Resolved(id, nameOf(id)))
                : plugin.names().resolve(rest[0], plugin.settings().lookup());
        who.whenComplete((w, e) -> {
            if (e != null) { reply(sender, req, nameError(e, rest[0])); return; }
            plugin.store().load(w.id(), w.name()).orTimeout(30, TimeUnit.SECONDS).whenComplete((state, e2) -> plugin.runMain(() -> {
                if (e2 != null) { reply(sender, req, error("luckperms_failed", "LuckPerms couldn't load " + (w.name() == null ? w.id() : w.name()) + ".")); return; }
                Settings s = plugin.settings();
                JsonObject o = ok();
                o.addProperty("uuid", w.id().toString());
                o.addProperty("name", w.name());
                o.addProperty("whitelisted", Bukkit.getOfflinePlayer(w.id()).isWhitelisted());
                JsonArray has = new JsonArray(), inherited = new JsonArray();
                for (Profile p : s.profiles().values()) {
                    if (state.direct().contains(p.group())) has.add(p.id());
                    else if (state.inherited().contains(p.group())) inherited.add(p.id());
                }
                o.add("profiles", has);
                o.add("throughOtherGroups", inherited);
                o.add("circles", ChangeService.strings(Planner.circles(s, state.inherited())));
                reply(sender, req, o);
            }));
        });
    }

    private void apply(CommandSender sender, String req, String[] rest) {
        if (rest.length < 2) {
            reply(sender, req, error("bad_request", "Use: stationmaster apply <request-id> <uuid> <name> [+profile] [-profile] [confirm=code,code] [whitelist=no] [via=leaf-juice]"));
            return;
        }
        UUID id = parseUuid(rest[0]);
        if (id == null) { reply(sender, req, error("bad_request", rest[0] + " isn't a UUID.")); return; }
        if (!NameResolver.possibleName(rest[1])) { reply(sender, req, error("bad_name", "\"" + rest[1] + "\" can't be a Minecraft name.")); return; }
        Set<String> add = new LinkedHashSet<>(), remove = new LinkedHashSet<>(), confirmed = new LinkedHashSet<>();
        boolean whitelist = true;
        String via = "console";
        for (int i = 2; i < rest.length; i++) {
            String a = rest[i];
            String lower = a.toLowerCase(Locale.ROOT);
            if (a.startsWith("+") && a.length() > 1) add.add(lower.substring(1));
            else if (a.startsWith("-") && a.length() > 1) remove.add(lower.substring(1));
            else if (lower.startsWith("confirm=")) { for (String c : lower.substring(8).split(",")) if (!c.isBlank()) confirmed.add(c.trim()); }
            else if (lower.equals("whitelist=no")) whitelist = false;
            else if (lower.equals("whitelist=yes")) whitelist = true;
            else if (lower.startsWith("via=") && VIA.matcher(lower.substring(4)).matches()) via = lower.substring(4);
            else { reply(sender, req, error("bad_request", "Didn't understand \"" + a + "\". Profiles go as +id or -id.")); return; }
        }
        var request = new ChangeService.Request(req, ChangeService.Actor.console(via), id, rest[1], add, remove, confirmed, whitelist);
        plugin.changes().submit(request).whenComplete((result, e) -> {
            if (e != null || result == null) reply(sender, req, error("internal", "Something went wrong inside Stationmaster. See the server log."));
            else reply(sender, req, result.json());
        });
    }

    private JsonObject attempts() {
        JsonObject o = ok();
        JsonArray list = new JsonArray();
        for (JoinAttempts.Attempt a : plugin.attempts().list()) {
            JsonObject j = new JsonObject();
            j.addProperty("uuid", a.uuid);
            j.addProperty("name", a.name);
            j.addProperty("attempts", a.attempts);
            j.addProperty("first", a.first);
            j.addProperty("last", a.last);
            list.add(j);
        }
        o.add("attempts", list);
        return o;
    }

    private void log(CommandSender sender, String req, String[] rest) {
        int count = 20;
        if (rest.length == 1) try { count = Math.max(1, Math.min(200, Integer.parseInt(rest[0]))); } catch (NumberFormatException e) {
            reply(sender, req, error("bad_request", "Use: stationmaster log <request-id> [count]"));
            return;
        }
        if (plugin.audit() == null) { reply(sender, req, error("log_failed", "changes.log can't be opened.")); return; }
        try {
            JsonObject o = ok();
            JsonArray entries = new JsonArray();
            for (JsonObject e : plugin.audit().tail(count)) entries.add(e);
            o.add("entries", entries);
            reply(sender, req, o);
        } catch (Exception e) {
            reply(sender, req, error("log_failed", "changes.log couldn't be read."));
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return s.length() == 32 ? NameResolver.parseUndashed(s) : s.length() == 36 ? UUID.fromString(s) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The name the server knows for an account, or null. Null is passed on so LuckPerms never stores a made-up name. */
    private static String nameOf(UUID id) {
        return Bukkit.getOfflinePlayer(id).getName();
    }

    @Override
    public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) return List.of();
        String start = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return (isConsole(source.getSender()) ? SUBS : List.of("reload")).stream().filter(s -> s.startsWith(start)).toList();
    }
}
