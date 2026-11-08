package com.extantape.stationmaster;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sends people to the lobby when they're in a world their profiles no longer open. A world counts as closed to someone
 * when some profile lists it and they have none of the profiles that list it. Worlds no profile lists are left alone.
 * The teleport is an ordinary one, so Multiverse-Inventories keeps their inventory with the world they left.
 */
public final class WorldAccess implements Listener {

    private final StationmasterPlugin plugin;

    WorldAccess(StationmasterPlugin plugin) { this.plugin = plugin; }

    public static boolean closed(Settings settings, Set<String> groups, String world) {
        boolean listed = false;
        for (Profile p : settings.profiles().values()) {
            if (!p.opens(world)) continue;
            if (groups.contains(p.group())) return false;
            listed = true;
        }
        return listed;
    }

    /** After a change: friendly lines for what was added or removed, then home if their world closed. */
    void afterChange(Player player, Settings settings, Set<String> groups, List<String> added, List<String> removed) {
        for (String id : added) {
            Profile p = settings.profiles().get(id);
            if (p == null) continue;
            player.sendMessage(Component.text(p.granted() != null ? p.granted() : "You've been given " + p.label() + "!", NamedTextColor.GREEN));
        }
        for (String id : removed) {
            Profile p = settings.profiles().get(id);
            if (p != null) player.sendMessage(Component.text(p.label() + " has been taken off your profiles.", NamedTextColor.GRAY));
        }
        checkWorld(player, settings, groups);
    }

    boolean checkWorld(Player player, Settings settings, Set<String> groups) {
        String world = player.getWorld().getName();
        if (!closed(settings, groups, world)) return false;
        World lobby = lobby(settings);
        if (lobby == null || lobby.getName().equals(world)) {
            plugin.getLogger().warning(player.getName() + " is in " + world + ", which their profiles don't open, but there's no other lobby world to send them to. Check lobby-world.");
            return false;
        }
        if (player.isDead()) return false; // handled on respawn
        if (player.isInsideVehicle()) player.leaveVehicle();
        player.teleportAsync(lobby.getSpawnLocation()).thenAccept(ok -> {
            if (ok) {
                player.sendMessage(Component.text(world + " isn't open to you anymore, so you're back in the lobby. Your things there are safe.", NamedTextColor.YELLOW));
                plugin.getLogger().info("Sent " + player.getName() + " from " + world + " to " + lobby.getName() + ", since their profiles no longer open it.");
            } else {
                plugin.getLogger().warning("Couldn't move " + player.getName() + " out of " + world + ". Another plugin may have blocked the teleport.");
            }
        });
        return true;
    }

    World lobby(Settings settings) {
        if (!settings.lobbyWorld().isBlank()) {
            World w = Bukkit.getWorld(settings.lobbyWorld());
            if (w != null) return w;
            plugin.getLogger().warning("lobby-world is set to " + settings.lobbyWorld() + ", but there's no world by that name. Using the main world.");
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    /** What LuckPerms says right now, through Bukkit's permission view: group.&lt;name&gt; is set for every inherited group. */
    static Set<String> groupsOf(Player player, Settings settings) {
        Set<String> out = new LinkedHashSet<>();
        for (Profile p : settings.profiles().values()) if (player.hasPermission("group." + p.group())) out.add(p.group());
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // A tick later, so LuckPerms has set up their permissions.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !plugin.store().ready() || !plugin.settings().ok()) return;
            checkWorld(player, plugin.settings(), groupsOf(player, plugin.settings()));
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Settings settings = plugin.settings();
        if (!plugin.store().ready() || !settings.ok()) return;
        Location to = event.getRespawnLocation();
        if (to.getWorld() == null || !closed(settings, groupsOf(event.getPlayer(), settings), to.getWorld().getName())) return;
        World lobby = lobby(settings);
        if (lobby != null) event.setRespawnLocation(lobby.getSpawnLocation());
    }
}
