package com.extantape.usher;

import com.extantape.usher.api.UsherProblemEvent;
import com.google.gson.JsonObject;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class UsherPlugin extends JavaPlugin {

    private volatile Settings settings = Settings.broken("not loaded yet");
    private volatile GroupStore store = new LuckPermsStore.Missing();
    private AuditLog audit;
    private JoinAttempts attempts;
    private WorldAccess access;
    private ChangeService changes;
    private NameResolver names;
    private PickerMenus menus;

    @Override
    public void onEnable() {
        migrateFromStationmaster();
        saveDefaultConfig();
        settings = readSettings();
        report(settings);

        try {
            audit = new AuditLog(getDataFolder().toPath().resolve("changes.log"));
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Can't open changes.log, so Usher will refuse every change", e);
            settings = Settings.broken("changes.log can't be opened");
            audit = null;
        }
        if (audit != null) for (String line : audit.unreadable())
            getLogger().warning("changes.log has a line that isn't readable, probably cut short by a crash: " + abbreviate(line));

        store = connectLuckPerms();
        attempts = new JoinAttempts(this, getDataFolder().toPath().resolve("join-attempts.json"));
        access = new WorldAccess(this);
        changes = new ChangeService(this);
        Executor async = r -> Bukkit.getScheduler().runTaskAsynchronously(this, r);
        names = new NameResolver(async, attempts::byName);
        menus = new PickerMenus(this);

        getServer().getPluginManager().registerEvents(attempts, this);
        getServer().getPluginManager().registerEvents(access, this);
        getServer().getPluginManager().registerEvents(menus, this);
        LatchkeyHook.register(this);

        PlayerCommands players = new PlayerCommands(this);
        ConsoleCommands console = new ConsoleCommands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var commands = event.registrar();
            commands.register("approve", "Let someone in and choose their profiles.", players.approve());
            commands.register("profile", "See or change someone's profiles.", players.profile());
            commands.register("usher", "Console commands for Leaf Juice and scripts, and reload.", console);
        });

        // After startup, so listeners from other plugins hear about problems.
        Bukkit.getScheduler().runTask(this, () -> {
            checkProfilesAgainstLuckPerms();
            if (!settings.ok()) callProblem("config_broken", settings.problems().get(0));
            else for (String p : settings.problems()) callProblem("config_problem", p);
            if (!store.ready()) callProblem("luckperms_missing", "LuckPerms isn't running, so Usher can't change anyone's profiles.");
            if (audit != null) recoverUnfinished();
        });
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.closeAll();
        if (changes != null) changes.shutdown();
    }

    private GroupStore connectLuckPerms() {
        if (!getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            getLogger().severe("LuckPerms isn't running. Usher will load, but it will refuse every change until LuckPerms works.");
            return new LuckPermsStore.Missing();
        }
        try {
            LuckPerms lp = getServer().getServicesManager().load(LuckPerms.class);
            if (lp != null) return new LuckPermsStore(lp);
        } catch (Throwable e) {
            getLogger().log(Level.SEVERE, "Couldn't reach LuckPerms' API", e);
        }
        getLogger().severe("LuckPerms is loaded but its API isn't available, so Usher will refuse every change.");
        return new LuckPermsStore.Missing();
    }

    Settings readSettings() {
        Path file = getDataFolder().toPath().resolve("config.yml");
        try {
            return Settings.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Settings.broken("config.yml can't be read: " + e.getMessage());
        }
    }

    /** Re-reads config.yml. A broken file keeps the settings already in use. Returns lines for whoever asked. */
    List<String> reload() {
        Settings fresh = readSettings();
        if (!fresh.ok()) {
            report(fresh);
            callProblem("config_broken", fresh.problems().get(0));
            return List.of("Couldn't reload: " + fresh.problems().get(0), settings.ok() ? "Still using the settings from before." : "Usher still refuses every change until config.yml is fixed.");
        }
        settings = fresh;
        report(fresh);
        List<String> unsafe = checkProfilesAgainstLuckPerms();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        out.add("Reloaded: " + settings.profiles().size() + " profiles.");
        out.addAll(fresh.problems());
        out.addAll(unsafe);
        return out;
    }

    private void report(Settings s) {
        if (!s.ok()) getLogger().severe(s.problems().get(0) + " Usher will refuse every change until it's fixed.");
        else for (String p : s.problems()) getLogger().warning(p);
    }

    /** Leaves out profiles whose group is missing or unsafe in LuckPerms, so the menu never offers them. */
    private List<String> checkProfilesAgainstLuckPerms() {
        if (!store.ready() || !settings.ok()) return List.of();
        java.util.ArrayList<String> problems = new java.util.ArrayList<>();
        java.util.LinkedHashMap<String, Profile> keep = new java.util.LinkedHashMap<>();
        for (Profile p : settings.profiles().values()) {
            String why = Planner.unsafe(settings, store, p);
            if (why == null) { keep.put(p.id(), p); continue; }
            String line = "Profile " + p.id() + " is left out: " + why.replace(" Nothing was changed.", "").replace(", so nothing was changed.", ".")
                    + (why.contains("doesn't exist") ? " Create the group, then run usher reload." : "");
            problems.add(line);
            getLogger().warning(line);
            callProblem(why.contains("doesn't exist") ? "config_problem" : "restricted", line);
        }
        if (keep.size() != settings.profiles().size()) {
            java.util.ArrayList<String> all = new java.util.ArrayList<>(settings.problems());
            all.addAll(problems);
            settings = new Settings(true, settings.lobbyWorld(), settings.lookup(), settings.restricted(),
                    java.util.Collections.unmodifiableMap(keep), List.copyOf(all));
        }
        return problems;
    }

    /** A change cut off by a crash: report what LuckPerms and the whitelist hold now, and log it. Nothing is redone. */
    private void recoverUnfinished() {
        List<JsonObject> open;
        try { open = audit.unfinished(); } catch (IOException e) { getLogger().log(Level.WARNING, "Couldn't read changes.log", e); return; }
        if (open.isEmpty()) return;
        if (!store.ready()) { getLogger().warning(open.size() + " change(s) were cut off by a stop, but LuckPerms isn't running to check them."); return; }
        for (JsonObject o : open) {
            UUID id = UUID.fromString(o.get("targetUuid").getAsString());
            String name = o.get("target").getAsString();
            store.load(id, name).orTimeout(30, TimeUnit.SECONDS).whenComplete((state, error) -> runMain(() -> {
                OfflinePlayer target = Bukkit.getOfflinePlayer(id);
                JsonObject e = new JsonObject();
                e.add("seq", o.get("seq"));
                e.addProperty("event", "interrupted");
                if (o.has("request")) e.add("request", o.get("request"));
                e.addProperty("target", name);
                e.addProperty("targetUuid", id.toString());
                e.addProperty("whitelistedNow", target.isWhitelisted());
                String msg;
                if (state != null) {
                    e.add("groupsNow", ChangeService.strings(state.direct()));
                    Set<String> wanted = new java.util.HashSet<>();
                    if (o.has("addGroups")) o.getAsJsonArray("addGroups").forEach(x -> wanted.add(x.getAsString()));
                    boolean groupsDone = state.direct().containsAll(wanted) && (!o.has("removeGroups")
                            || o.getAsJsonArray("removeGroups").asList().stream().noneMatch(x -> state.direct().contains(x.getAsString())));
                    boolean wlWanted = o.has("whitelist") && o.get("whitelist").getAsBoolean();
                    e.addProperty("groupsApplied", groupsDone);
                    msg = "A change for " + name + " was cut off by a server stop. LuckPerms " + (groupsDone ? "has" : "doesn't have")
                            + " the new groups, and they're " + (target.isWhitelisted() ? "" : "not ") + "on the whitelist."
                            + (groupsDone && wlWanted && !target.isWhitelisted() ? " Approve them again to finish." : "");
                } else {
                    msg = "A change for " + name + " was cut off by a server stop, and LuckPerms couldn't be read to check it.";
                }
                e.addProperty("message", msg);
                try { audit.write(e); } catch (IOException ex) { getLogger().log(Level.WARNING, "Couldn't write changes.log", ex); }
                getLogger().warning(msg);
                callProblem("interrupted", msg);
            }));
        }
    }

    void callProblem(String code, String message) {
        Bukkit.getPluginManager().callEvent(new UsherProblemEvent(code, message, null, null));
    }

    void runMain(Runnable r) {
        if (!isEnabled()) return;
        if (Bukkit.isPrimaryThread()) r.run(); else Bukkit.getScheduler().runTask(this, r);
    }

    private static String abbreviate(String s) { return s.length() > 120 ? s.substring(0, 120) + "…" : s; }

    public Settings settings() { return settings; }
    public GroupStore store() { return store; }
    AuditLog audit() { return audit; }
    JoinAttempts attempts() { return attempts; }
    WorldAccess access() { return access; }
    ChangeService changes() { return changes; }
    NameResolver names() { return names; }
    PickerMenus menus() { return menus; }


    /**
     * The plugin was called Stationmaster before 0.3.0. If plugins/Usher doesn't exist yet and plugins/Stationmaster
     * does, copy it across (config, changes.log, join-attempts.json) so nothing is lost. The old folder is left as it
     * was, as a backup. Nothing is overwritten: if plugins/Usher exists, this does nothing.
     */
    private void migrateFromStationmaster() {
        Path now = getDataFolder().toPath();
        Path old = now.resolveSibling("Stationmaster");
        String done = Migration.copyIfMissing(old, now);
        if (done != null) getLogger().warning(done);
    }
}
