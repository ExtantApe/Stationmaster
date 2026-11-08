package com.extantape.stationmaster;

import com.extantape.stationmaster.api.ProfilesChangedEvent;
import com.extantape.stationmaster.api.StationmasterProblemEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Applies changes one at a time, in the order they arrive, so two admins editing the same person can't lose each
 * other's work: each change is worked out against what LuckPerms holds when its turn comes.
 *
 * <p>Order of a change: checks, then a {@code started} log line, then LuckPerms groups (saved), then the whitelist, then
 * the {@code done} line. If LuckPerms fails, the whitelist is never touched.
 */
public final class ChangeService {

    public static final String PERMISSION = "stationmaster.approve";

    /** Who asked. {@code uuid} is null for the console. {@code via} is menu, console or leaf-juice. */
    public record Actor(String name, UUID uuid, String via) {
        public static Actor console(String via) { return new Actor("Console", null, via); }
        public boolean isConsole() { return uuid == null; }
    }

    public record Request(String requestId, Actor actor, UUID target, String targetName, Set<String> add,
                          Set<String> remove, Set<String> confirmed, boolean whitelist) {}

    /** status is ok, warn (nothing done, needs a yes), refused or error. */
    public record Result(String status, String code, String message, List<Planner.Warning> warnings, JsonObject json) {
        public boolean ok() { return status.equals("ok"); }
    }

    private final StationmasterPlugin plugin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Stationmaster changes");
        t.setDaemon(true);
        return t;
    });

    ChangeService(StationmasterPlugin plugin) { this.plugin = plugin; }

    public CompletableFuture<Result> submit(Request request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return run(request);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "A change for " + request.targetName() + " failed unexpectedly", e);
                return result(request, "error", "internal", "Something went wrong inside Stationmaster, so the change stopped. See the server log.", List.of(), null, false, false);
            }
        }, worker);
    }

    void shutdown() { worker.shutdownNow(); }

    private Result run(Request r) {
        Settings settings = plugin.settings();
        GroupStore store = plugin.store();
        AuditLog audit = plugin.audit();
        if (audit == null) return result(r, "error", "log_failed", "Stationmaster can't open its change log, so nothing was changed.", List.of(), null, false, false);

        if (r.requestId() != null) {
            JsonObject previous = audit.resultFor(r.requestId());
            if (previous != null) {
                JsonObject json = previous.has("result") ? previous.getAsJsonObject("result") : new JsonObject();
                json.addProperty("replayed", true);
                String status = json.has("status") ? json.get("status").getAsString() : "ok";
                String code = json.has("code") ? json.get("code").getAsString() : "replayed";
                String message = json.has("message") ? json.get("message").getAsString() : "Already handled.";
                return new Result(status, code, message, List.of(), json);
            }
        }

        // Checks that need the main thread: permissions and the whitelist.
        String[] pre = callMain(() -> {
            if (!plugin.settings().ok()) return new String[]{"config_broken", "Stationmaster's config is broken, so nothing was changed. " + plugin.settings().problems().get(0)};
            if (!plugin.store().ready()) return new String[]{"luckperms_missing", "LuckPerms isn't running, so nothing was changed."};
            if (!r.actor().isConsole()) {
                Player admin = Bukkit.getPlayer(r.actor().uuid());
                if (admin == null || !admin.hasPermission(PERMISSION)) return new String[]{"not_permitted", "You don't have permission to approve people or change profiles."};
                if (admin.getUniqueId().equals(r.target())) return new String[]{"self", "You can't approve yourself or change your own profiles. Ask another admin."};
            }
            return new String[]{null, Boolean.toString(Bukkit.getOfflinePlayer(r.target()).isWhitelisted())};
        });
        if (pre == null) return result(r, "error", "server_busy", "The server didn't respond in time, so nothing was changed.", List.of(), null, false, false);
        if (pre[0] != null) return refuse(r, pre[0], pre[1]);
        boolean wasWhitelisted = Boolean.parseBoolean(pre[1]);

        GroupStore.UserState state;
        try {
            state = store.load(r.target(), r.targetName()).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return luckPermsFailed(r, null, e);
        }

        Planner.Plan plan = Planner.plan(settings, store, state, r.add(), r.remove(), r.confirmed());
        if (plan.refused()) return refuse(r, plan.refusal(), plan.refusalText());
        if (!plan.unconfirmed().isEmpty()) {
            return result(r, "warn", "needs_confirmation", "This change needs a yes first. Nothing was changed yet.", plan.unconfirmed(), plan, wasWhitelisted, false);
        }
        boolean whitelistNow = r.whitelist() && !wasWhitelisted;
        if (plan.nothingToDo() && !whitelistNow) {
            Result res = result(r, "ok", "nothing_to_change", nothingMessage(r, plan, settings), List.of(), plan, wasWhitelisted, false);
            log(audit, entry(r, 0, "nochange", plan, settings, res));
            return res;
        }

        long seq = audit.next();
        try {
            audit.write(entry(r, seq, "started", plan, settings, null));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't write changes.log", e);
            return problem(r, "log_failed", "Stationmaster couldn't write its change log, so nothing was changed.");
        }

        pause("stationmaster.test.pause-before-groups");
        GroupStore.UserState after;
        try {
            after = plan.addGroups().isEmpty() && plan.removeGroups().isEmpty() ? state
                    : store.modify(r.target(), r.targetName(), plan.addGroups(), plan.removeGroups()).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            return luckPermsFailed(r, seq, e);
        }
        pause("stationmaster.test.pause-after-groups");

        List<String> added = ids(settings, plan.addGroups()), removed = ids(settings, plan.removeGroups());
        Boolean whitelisted = callMain(() -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(r.target());
            if (whitelistNow) target.setWhitelisted(true);
            plugin.attempts().remove(r.target());
            Player online = target.getPlayer();
            if (online != null) plugin.access().afterChange(online, settings, after.inherited(), added, removed);
            Bukkit.getPluginManager().callEvent(new ProfilesChangedEvent(r.target(), r.targetName(), r.actor().name(), r.actor().via(),
                    r.requestId(), added, removed, List.copyOf(r.confirmed()), whitelistNow));
            return target.isWhitelisted();
        });

        if (whitelistNow && !Boolean.TRUE.equals(whitelisted)) {
            String msg = "The groups were saved, but adding " + r.targetName() + " to the whitelist didn't work. Run /whitelist add " + r.targetName() + ".";
            Result res = result(r, "error", "whitelist_failed", msg, List.of(), plan, false, false);
            log(audit, entry(r, seq, "failed", plan, settings, res));
            fireProblem("whitelist_failed", msg, r);
            return res;
        }
        Result res = result(r, "ok", "applied", doneMessage(r, added, removed, whitelistNow, plan, settings), List.of(), plan, whitelistNow || wasWhitelisted, whitelistNow);
        res.json().add("groupsNow", strings(after.direct()));
        log(audit, entry(r, seq, "done", plan, settings, res));
        return res;
    }

    private Result refuse(Request r, String code, String message) {
        Result res = result(r, "refused", code, message, List.of(), null, false, false);
        log(plugin.audit(), entry(r, 0, "refused", null, plugin.settings(), res));
        if (!code.equals("unknown_profile") && !code.equals("conflict")) fireProblem(code, message, r);
        return res;
    }

    private Result problem(Request r, String code, String message) {
        fireProblem(code, message, r);
        return result(r, "error", code, message, List.of(), null, false, false);
    }

    private Result luckPermsFailed(Request r, Long seq, Exception e) {
        plugin.getLogger().log(Level.WARNING, "LuckPerms failed during a change for " + r.targetName(), e);
        String msg = "LuckPerms couldn't save the change, so nothing was changed and " + r.targetName() + " wasn't whitelisted. Check the server log.";
        Result res = result(r, "error", "luckperms_failed", msg, List.of(), null, false, false);
        if (seq != null) log(plugin.audit(), entry(r, seq, "failed", null, plugin.settings(), res));
        fireProblem("luckperms_failed", msg, r);
        return res;
    }

    private void fireProblem(String code, String message, Request r) {
        plugin.runMain(() -> Bukkit.getPluginManager().callEvent(new StationmasterProblemEvent(code, message, r.target(), r.actor().name())));
    }

    private void log(AuditLog audit, JsonObject entry) {
        try {
            audit.write(entry);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't write changes.log", e);
        }
    }

    private static void pause(String property) {
        long ms = Long.getLong(property, 0L);
        if (ms > 0) try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private <T> T callMain(Callable<T> task) {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> ids(Settings s, Collection<String> groups) {
        List<String> out = new ArrayList<>();
        for (String g : groups) { Profile p = s.byGroup(g); out.add(p == null ? g : p.id()); }
        return out;
    }

    private static String labels(Settings s, Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) { Profile p = s.profiles().get(id); out.add(p == null ? id : p.label()); }
        return join(out);
    }

    static String join(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static String doneMessage(Request r, List<String> added, List<String> removed, boolean whitelisted, Planner.Plan plan, Settings s) {
        StringBuilder sb = new StringBuilder();
        String name = r.targetName();
        if (whitelisted) {
            sb.append(name).append(" is approved and can join now");
            sb.append(added.isEmpty() ? ", with no profiles, so just the starting areas." : ", with " + labels(s, added) + ".");
        } else if (!added.isEmpty()) {
            sb.append(name).append(" now has ").append(labels(s, added)).append(".");
        }
        if (!removed.isEmpty()) sb.append(sb.isEmpty() ? "" : " ").append(labels(s, removed)).append(removed.size() == 1 ? " was" : " were").append(" taken off ").append(name).append(".");
        if (!plan.keptByInheritance().isEmpty()) sb.append(" They still get ").append(labels(s, plan.keptByInheritance())).append(" through another group, which Stationmaster can't change.");
        return sb.toString().trim();
    }

    private static String nothingMessage(Request r, Planner.Plan plan, Settings s) {
        StringBuilder sb = new StringBuilder("Nothing to change for ").append(r.targetName()).append(".");
        if (!plan.alreadyHad().isEmpty()) sb.append(" They already have ").append(labels(s, plan.alreadyHad())).append(".");
        if (!plan.didNotHave().isEmpty()) sb.append(" They didn't have ").append(labels(s, plan.didNotHave())).append(".");
        if (!plan.keptByInheritance().isEmpty()) sb.append(" They get ").append(labels(s, plan.keptByInheritance())).append(" through another group, which Stationmaster can't change.");
        return sb.toString();
    }

    private static Result result(Request r, String status, String code, String message, List<Planner.Warning> warnings,
                                 Planner.Plan plan, boolean whitelisted, boolean newlyWhitelisted) {
        JsonObject j = new JsonObject();
        j.addProperty("status", status);
        j.addProperty("code", code);
        j.addProperty("message", message);
        j.addProperty("uuid", r.target().toString());
        j.addProperty("name", r.targetName());
        if (plan != null && !plan.refused()) {
            j.add("added", strings(new LinkedHashSet<>(r.add()).stream().filter(id -> !plan.alreadyHad().contains(id)).toList()));
            j.add("removed", strings(r.remove().stream().filter(id -> !plan.didNotHave().contains(id) && !plan.keptByInheritance().contains(id)).toList()));
            j.add("alreadyHad", strings(plan.alreadyHad()));
            j.add("didNotHave", strings(plan.didNotHave()));
            j.add("keptByInheritance", strings(plan.keptByInheritance()));
        }
        JsonArray w = new JsonArray();
        for (Planner.Warning warning : warnings) {
            JsonObject o = new JsonObject();
            o.addProperty("code", warning.code());
            o.addProperty("text", warning.text());
            w.add(o);
        }
        j.add("warnings", w);
        j.addProperty("whitelisted", whitelisted);
        j.addProperty("newlyWhitelisted", newlyWhitelisted);
        return new Result(status, code, message, List.copyOf(warnings), j);
    }

    private static JsonObject entry(Request r, long seq, String event, Planner.Plan plan, Settings s, Result res) {
        JsonObject o = new JsonObject();
        if (seq > 0) o.addProperty("seq", seq);
        o.addProperty("event", event);
        if (r.requestId() != null) o.addProperty("request", r.requestId());
        o.addProperty("actor", r.actor().name());
        if (r.actor().uuid() != null) o.addProperty("actorUuid", r.actor().uuid().toString());
        o.addProperty("via", r.actor().via());
        o.addProperty("target", r.targetName());
        o.addProperty("targetUuid", r.target().toString());
        o.add("add", strings(r.add()));
        o.add("remove", strings(r.remove()));
        if (!r.confirmed().isEmpty()) o.add("confirmed", strings(r.confirmed()));
        o.addProperty("whitelist", r.whitelist());
        if (plan != null && !plan.refused()) {
            o.add("addGroups", strings(plan.addGroups()));
            o.add("removeGroups", strings(plan.removeGroups()));
        }
        if (res != null) o.add("result", res.json().deepCopy());
        return o;
    }

    static JsonArray strings(Collection<String> items) {
        JsonArray a = new JsonArray();
        for (String s : items) a.add(s);
        return a;
    }
}
