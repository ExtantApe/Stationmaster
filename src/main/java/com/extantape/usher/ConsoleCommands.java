package com.extantape.usher;

import com.extantape.signalbox.Answer;
import com.extantape.signalbox.Args;
import com.extantape.signalbox.Confirm;
import com.extantape.signalbox.Describe;
import com.extantape.signalbox.Errors;
import com.extantape.signalbox.Request;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * /usher: console commands for Leaf Juice, scripts and people. With {@code --json <id>} each answers with exactly one
 * Signalbox line, {@code ea-result <id> <json>}, which may come a moment later; without it, in plain words. Changes that
 * need a yes answer with the questions and change nothing; send them again with {@code --yes id,id}. See the README.
 */
final class ConsoleCommands implements BasicCommand {
    static final List<String> SUBS = List.of("profiles", "resolve", "show", "apply", "attempts", "log", "reload", "describe");
    private static final Pattern VIA = Pattern.compile("[a-z0-9-]{1,32}");
    static final int MAX_LIMIT = 100;

    private final UsherPlugin plugin;

    ConsoleCommands(UsherPlugin plugin) { this.plugin = plugin; }

    /** The console, and approvers in the game (who only get reload). Hidden from everyone else. */
    @Override
    public boolean canUse(CommandSender sender) { return isConsole(sender) || ChangeService.mayApprove(sender); }

    static boolean isConsole(CommandSender sender) { return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender; }

    static List<Describe.Command> described() {
        String a = ChangeService.PERMISSION;
        return List.of(
            new Describe.Command("profiles", "console", List.of(), List.of(), false),
            new Describe.Command("resolve", "console", List.of(Describe.Arg.of("name", "player")), List.of(), false),
            new Describe.Command("show", "console", List.of(Describe.Arg.of("player", "player")), List.of(), false),
            new Describe.Command("apply", "console", List.of(Describe.Arg.of("uuid", "uuid"), Describe.Arg.of("name", "player"),
                Describe.Arg.listOf("add", "string", List.of("none")), Describe.Arg.listOf("remove", "string", List.of("none")),
                Describe.Arg.of("whitelist", "bool").asOptional(), Describe.Arg.of("via", "string").asOptional()), List.of("confirm-<profile>", "bridge"), false),
            new Describe.Command("attempts", "console", List.of(Describe.Arg.of("limit", "int").asOptional(), Describe.Arg.of("cursor", "string").asOptional()), List.of(), false),
            new Describe.Command("log", "console", List.of(Describe.Arg.of("limit", "int").asOptional()), List.of(), false),
            new Describe.Command("reload", a, List.of(), List.of(), false),
            new Describe.Command("describe", "console", List.of(), List.of(), false));
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        boolean console = isConsole(sender);
        Request r;
        try {
            r = Request.parse(args);
        } catch (Request.Bad bad) {
            fail(sender, bad.id, bad.code, bad.getMessage());
            return;
        }
        List<String> t = r.tokens();
        if (t.isEmpty()) {
            if (r.json()) fail(sender, r.id(), Errors.UNKNOWN_COMMAND, "Give a command: " + String.join(", ", SUBS) + ".");
            else sender.sendMessage("Usher " + plugin.getPluginMeta().getVersion() + ": " + String.join(", ", SUBS) + ". See the README for details.");
            return;
        }
        String sub = t.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = t.subList(1, t.size());
        if (sub.equals("reload")) {
            if (!console && !ChangeService.mayApprove(sender)) { fail(sender, r.id(), Errors.NO_PERMISSION, "Only approvers can do that."); return; }
            List<String> lines = plugin.reload();
            if (r.json()) send(sender, Answer.ok(r.id(), Map.of("messages", lines, "configOk", plugin.settings().ok())));
            else for (String line : lines) sender.sendMessage(line);
            return;
        }
        if (!console) {
            fail(sender, r.id(), Errors.NO_PERMISSION, "Those are console commands. In the game, use /approve <name> or /profile <name>.");
            return;
        }
        try {
            switch (sub) {
                case "profiles" -> { none(rest); profiles(sender, r); }
                case "resolve" -> resolve(sender, r, rest);
                case "show" -> show(sender, r, rest);
                case "apply" -> apply(sender, r, rest);
                case "attempts" -> attempts(sender, r, rest);
                case "log" -> log(sender, r, rest);
                case "describe" -> {
                    none(rest);
                    if (r.json()) send(sender, Answer.ok(r.id(), Describe.body("Usher", plugin.getPluginMeta().getVersion(), described())));
                    else for (Describe.Command d : described()) sender.sendMessage("usher " + d.name());
                }
                default -> fail(sender, r.id(), Errors.UNKNOWN_COMMAND, "Unknown: " + sub + ". Try " + String.join(", ", SUBS) + ".");
            }
        } catch (Request.Bad bad) {
            fail(sender, r.id(), bad.code, bad.getMessage());
        } catch (Refusal no) {
            fail(sender, r.id(), no.code, no.getMessage());
        }
    }

    /** A command refused with a Signalbox error code and plain words. */
    static final class Refusal extends Exception {
        final String code;
        Refusal(String code, String message) { super(message); this.code = code; }
    }

    private static void none(List<String> rest) throws Refusal {
        if (!rest.isEmpty()) throw new Refusal(Errors.BAD_ARGUMENT, "That command takes no arguments.");
    }

    // --- profiles ---

    private void profiles(CommandSender sender, Request r) {
        Settings s = plugin.settings();
        if (!s.ok()) { fail(sender, r.id(), "config-broken", s.problems().get(0)); return; }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Profile p : s.profiles().values()) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("id", p.id());
            j.put("label", p.label());
            j.put("group", p.group());
            j.put("toggle", p.toggle());
            if (p.circle() != null) j.put("circle", p.circle());
            if (p.confirm() != null) j.put("confirm", p.confirm());
            j.put("worlds", List.copyOf(p.worlds()));
            j.put("description", List.copyOf(p.description()));
            list.add(j);
        }
        if (r.json()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("luckperms", plugin.store().ready());
            o.put("profiles", list);
            o.put("restricted", List.copyOf(s.restricted()));
            o.put("problems", List.copyOf(s.problems()));
            send(sender, Answer.ok(r.id(), o));
            return;
        }
        if (!plugin.store().ready()) sender.sendMessage("LuckPerms isn't running, so no change can be made.");
        for (Profile p : s.profiles().values()) sender.sendMessage(p.id() + ": " + p.label() + " (group " + p.group() + ")");
    }

    // --- resolve and show ---

    private void resolve(CommandSender sender, Request r, List<String> rest) throws Request.Bad, Refusal {
        if (rest.size() != 1) throw new Refusal(Errors.BAD_ARGUMENT, "Use: usher resolve <name>");
        String typed = Args.player(rest.get(0));
        plugin.names().resolve(typed, plugin.settings().lookup()).whenComplete((who, e) -> {
            if (e != null) { nameFail(sender, r, e, typed); return; }
            plugin.runMain(() -> {
                var seen = Bukkit.getOfflinePlayer(who.id());
                JoinAttempts.Attempt tried = plugin.attempts().of(who.id());
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("uuid", who.id().toString());
                o.put("name", who.name());
                o.put("whitelisted", seen.isWhitelisted());
                // So Leaf Juice can warn before approving an account that has never come near this server.
                o.put("playedBefore", seen.hasPlayedBefore());
                o.put("triedToJoin", tried == null ? 0 : tried.attempts);
                if (tried != null) o.put("lastTried", tried.last);
                if (r.json()) send(sender, Answer.ok(r.id(), o));
                else sender.sendMessage(who.name() + " is " + who.id() + (seen.isWhitelisted() ? ", whitelisted" : ", not whitelisted")
                    + (seen.hasPlayedBefore() ? ", has played here" : ", has never played here") + ".");
            });
        });
    }

    private void nameFail(CommandSender sender, Request r, Throwable e, String typed) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof NameResolver.NameProblem np) fail(sender, r.id(), np.code.replace('_', '-'), np.getMessage());
        else fail(sender, r.id(), Errors.BUSY, "Couldn't look up " + typed + ". Try again in a minute.");
    }

    private void show(CommandSender sender, Request r, List<String> rest) throws Request.Bad, Refusal {
        if (rest.size() != 1) throw new Refusal(Errors.BAD_ARGUMENT, "Use: usher show <uuid or name>");
        if (!plugin.store().ready()) throw new Refusal("luckperms-missing", "LuckPerms isn't running.");
        String typed = Args.player(rest.get(0));
        UUID id = typed.contains("-") ? UUID.fromString(typed) : null;
        CompletableFuture<NameResolver.Resolved> who = id != null ? CompletableFuture.completedFuture(new NameResolver.Resolved(id, nameOf(id)))
            : plugin.names().resolve(typed, plugin.settings().lookup());
        who.whenComplete((w, e) -> {
            if (e != null) { nameFail(sender, r, e, typed); return; }
            plugin.store().load(w.id(), w.name()).orTimeout(30, TimeUnit.SECONDS).whenComplete((state, e2) -> plugin.runMain(() -> {
                if (e2 != null) { fail(sender, r.id(), "luckperms-failed", "LuckPerms couldn't load " + (w.name() == null ? w.id() : w.name()) + "."); return; }
                Settings s = plugin.settings();
                List<String> has = new ArrayList<>(), inherited = new ArrayList<>();
                for (Profile p : s.profiles().values()) {
                    if (state.direct().contains(p.group())) has.add(p.id());
                    else if (state.inherited().contains(p.group())) inherited.add(p.id());
                }
                boolean whitelisted = Bukkit.getOfflinePlayer(w.id()).isWhitelisted();
                List<String> circles = List.copyOf(Planner.circles(s, state.inherited()));
                if (r.json()) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("uuid", w.id().toString());
                    o.put("name", w.name());
                    o.put("whitelisted", whitelisted);
                    o.put("profiles", has);
                    o.put("throughOtherGroups", inherited);
                    o.put("circles", circles);
                    send(sender, Answer.ok(r.id(), o));
                } else {
                    sender.sendMessage((w.name() == null ? w.id().toString() : w.name()) + ": " + (has.isEmpty() ? "no profiles" : String.join(", ", has))
                        + (inherited.isEmpty() ? "" : "; through other groups: " + String.join(", ", inherited))
                        + (circles.isEmpty() ? "" : "; circles: " + String.join(", ", circles)) + (whitelisted ? "; whitelisted." : "; not whitelisted."));
                }
            }));
        });
    }

    // --- apply ---

    /** The parsed arguments of apply, kept apart so the parsing can be tested without a server. */
    record ApplyArgs(UUID uuid, String name, Set<String> add, Set<String> remove, boolean whitelist, String via) {}

    static ApplyArgs parseApply(List<String> rest) throws Request.Bad, Refusal {
        if (rest.size() < 4 || rest.size() > 6)
            throw new Refusal(Errors.BAD_ARGUMENT, "Use: usher apply <uuid> <name> <add|none> <remove|none> [whitelist true|false] [via]");
        UUID id = Args.uuid(rest.get(0));
        String name = rest.get(1);
        if (!NameResolver.possibleName(name)) throw new Refusal(Errors.BAD_ARGUMENT, "\"" + name + "\" can't be a Minecraft name.");
        Set<String> add = profileList(rest.get(2)), remove = profileList(rest.get(3));
        boolean whitelist = rest.size() < 5 || Args.bool(rest.get(4));
        String via = rest.size() < 6 ? "console" : rest.get(5);
        if (!VIA.matcher(via).matches()) throw new Refusal(Errors.BAD_ARGUMENT, "via is 1 to 32 of a-z, 0-9 and -.");
        return new ApplyArgs(id, name, add, remove, whitelist, via);
    }

    private static Set<String> profileList(String token) throws Request.Bad, Refusal {
        if (token.equals("none")) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String p : Args.list(token)) {
            String id = p.toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,32}")) throw new Refusal(Errors.BAD_ARGUMENT, "Profile names are lowercase letters, numbers, dashes and underscores: " + p + " isn't one.");
            out.add(id);
        }
        return out;
    }

    private void apply(CommandSender sender, Request r, List<String> rest) throws Request.Bad, Refusal {
        ApplyArgs a = parseApply(rest);
        Set<String> confirmed = LatchkeyHook.confirmedCodes(plugin.settings(), r.yes());
        var request = new ChangeService.Request(r.id(), ChangeService.Actor.console(a.via()), a.uuid(), a.name(), a.add(), a.remove(), confirmed, a.whitelist());
        plugin.changes().submit(request).whenComplete((result, e) -> plugin.runMain(() -> {
            if (e != null || result == null) { fail(sender, r.id(), Errors.INTERNAL, "Something went wrong inside Usher. See the server log."); return; }
            answer(sender, r, result);
        }));
    }

    /** A change's result as one answer: done, questions that need a yes, or a refusal with its reason. */
    private void answer(CommandSender sender, Request r, ChangeService.Result result) {
        String code = result.code().replace('_', '-');
        switch (result.status()) {
            case "ok" -> {
                if (r.json()) send(sender, Answer.ok(r.id(), toMap(result.json())));
                else sender.sendMessage(result.message());
            }
            case "warn" -> {
                List<Confirm.Question> qs = new ArrayList<>();
                for (Planner.Warning w : result.warnings()) qs.add(new Confirm.Question(LatchkeyHook.toYesId(w.code()), w.text()));
                if (r.json()) send(sender, Answer.needs(r.id(), qs));
                else {
                    sender.sendMessage(result.message());
                    for (Confirm.Question q : qs) sender.sendMessage("  " + q.id() + ": " + q.question());
                    sender.sendMessage("If that's all right, run it again with --yes " + String.join(",", qs.stream().map(Confirm.Question::id).toList()) + " at the end.");
                }
            }
            default -> fail(sender, r.id(), code, result.message());
        }
    }

    // --- attempts and log ---

    private void attempts(CommandSender sender, Request r, List<String> rest) throws Request.Bad, Refusal {
        if (rest.size() > 2) throw new Refusal(Errors.BAD_ARGUMENT, "Use: usher attempts [limit] [cursor]");
        int limit = rest.isEmpty() ? 50 : Args.integer(rest.get(0));
        int cursor = rest.size() < 2 ? 0 : cursorOf(rest.get(1));
        if (limit < 1 || limit > MAX_LIMIT || cursor < 0) throw new Refusal(Errors.BAD_ARGUMENT, "limit is 1 to " + MAX_LIMIT + " and cursor 0 or more.");
        List<JoinAttempts.Attempt> all = new ArrayList<>(plugin.attempts().list());
        List<JoinAttempts.Attempt> page = all.subList(Math.min(cursor, all.size()), Math.min(cursor + limit, all.size()));
        if (!r.json()) {
            if (all.isEmpty()) sender.sendMessage("Nobody has tried to join without being on the whitelist.");
            for (JoinAttempts.Attempt a : page) sender.sendMessage(a.name + " (" + a.uuid + "): " + a.attempts + " tries");
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (JoinAttempts.Attempt a : page) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("uuid", a.uuid);
            j.put("name", a.name);
            j.put("attempts", a.attempts);
            j.put("first", a.first);
            j.put("last", a.last);
            list.add(j);
        }
        // The spec's list shape: items, and next as an opaque string cursor, null on the last page.
        send(sender, Answer.page(r.id(), list, cursor + page.size() < all.size() ? Integer.toString(cursor + page.size()) : null));
    }

    private void log(CommandSender sender, Request r, List<String> rest) throws Request.Bad, Refusal {
        if (rest.size() > 1) throw new Refusal(Errors.BAD_ARGUMENT, "Use: usher log [limit]");
        int count = rest.isEmpty() ? 20 : Args.integer(rest.get(0));
        if (count < 1 || count > MAX_LIMIT) throw new Refusal(Errors.BAD_ARGUMENT, "limit is 1 to " + MAX_LIMIT + ".");
        if (plugin.audit() == null) throw new Refusal("log-failed", "changes.log can't be opened.");
        List<JsonObject> entries;
        try { entries = plugin.audit().tail(count); }
        catch (Exception e) { throw new Refusal("log-failed", "changes.log couldn't be read."); }
        if (r.json()) {
            List<Object> list = new ArrayList<>();
            for (JsonObject e : entries) list.add(toJava(e));
            send(sender, Answer.ok(r.id(), Map.of("entries", list)));
        } else {
            for (JsonObject e : entries) sender.sendMessage(e.toString());
        }
    }

    // --- helpers ---

    /** The cursor a page handed out: a string to the caller, a position here. Anything else is refused. */
    static int cursorOf(String token) throws Refusal {
        if (!token.matches("\\d{1,9}")) throw new Refusal(Errors.BAD_ARGUMENT, "That cursor isn't one Usher handed out. Use the next value from the last page.");
        return Integer.parseInt(token);
    }

    private void send(CommandSender sender, String line) { plugin.runMain(() -> Answer.send(sender::sendMessage, line)); }

    private void fail(CommandSender sender, String id, String code, String message) {
        if (id != null) send(sender, Answer.error(id, code, message));
        else plugin.runMain(() -> sender.sendMessage(PlayerCommands.bad(message)));
    }

    /** Gson's tree as plain maps and lists, which Signalbox writes. */
    static Map<String, Object> toMap(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (var e : o.entrySet()) m.put(e.getKey(), toJava(e.getValue()));
        return m;
    }

    static Object toJava(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) return toMap(e.getAsJsonObject());
        if (e.isJsonArray()) { List<Object> l = new ArrayList<>(); for (JsonElement x : (JsonArray) e) l.add(toJava(x)); return l; }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) { double d = p.getAsDouble(); return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) p.getAsLong() : (Object) d; }
        return p.getAsString();
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
