package com.extantape.stationmaster;

import com.extantape.stationmaster.api.JoinAttemptEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Remembers who was turned away by the whitelist, so they can be approved from the console or Leaf Juice. The list is
 * capped at 100 accounts and repeat tries only bump a count, so nobody can flood it. Approving someone removes them.
 */
public final class JoinAttempts implements Listener {

    public static final class Attempt {
        public String uuid;
        public String name;
        public int attempts;
        public String first;
        public String last;
        transient long lastEventMillis;
    }

    private static final int CAP = 100;
    private static final long EVENT_GAP_MS = 10 * 60 * 1000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final StationmasterPlugin plugin;
    private final Path file;
    private final Map<UUID, Attempt> attempts = new LinkedHashMap<>();

    JoinAttempts(StationmasterPlugin plugin, Path file) {
        this.plugin = plugin;
        this.file = file;
        try {
            if (Files.exists(file)) {
                List<Attempt> list = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Attempt>>() {}.getType());
                if (list != null) for (Attempt a : list) attempts.put(UUID.fromString(a.uuid), a);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "join-attempts.json couldn't be read, so the list starts empty", e);
        }
    }

    public synchronized List<Attempt> list() { return new ArrayList<>(attempts.values()); }

    public synchronized void remove(UUID id) {
        if (attempts.remove(id) != null) save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !Bukkit.hasWhitelist()) return;
        UUID id = event.getUniqueId();
        OfflinePlayer who = Bukkit.getOfflinePlayer(id);
        if (who.isWhitelisted() || who.isOp()) return;
        Attempt fire = record(id, event.getName());
        if (fire != null) {
            int count = fire.attempts;
            plugin.getLogger().info(event.getName() + " (" + id + ") tried to join but isn't on the whitelist.");
            plugin.runMain(() -> Bukkit.getPluginManager().callEvent(new JoinAttemptEvent(id, event.getName(), count)));
        }
    }

    /** Records a try and returns it if an event should fire for it, or null if one fired recently. */
    synchronized Attempt record(UUID id, String name) {
        String now = Instant.now().toString();
        Attempt a = attempts.remove(id);
        if (a == null) {
            a = new Attempt();
            a.uuid = id.toString();
            a.first = now;
        }
        a.name = name;
        a.last = now;
        a.attempts++;
        attempts.put(id, a);
        while (attempts.size() > CAP) attempts.remove(attempts.keySet().iterator().next());
        save();
        long t = System.currentTimeMillis();
        if (t - a.lastEventMillis < EVENT_GAP_MS) return null;
        a.lastEventMillis = t;
        return a;
    }

    private void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(new ArrayList<>(attempts.values())), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't save join-attempts.json", e);
        }
    }
}
