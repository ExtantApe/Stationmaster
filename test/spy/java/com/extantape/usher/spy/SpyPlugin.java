package com.extantape.usher.spy;

import com.extantape.usher.api.JoinAttemptEvent;
import com.extantape.usher.api.ProfilesChangedEvent;
import com.extantape.usher.api.UsherProblemEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Test only: prints every Usher event as one SPY line, the way a Discord bridge would see it. */
public final class SpyPlugin extends JavaPlugin implements Listener {
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }

    @EventHandler public void on(ProfilesChangedEvent e) {
        getLogger().info("SPY changed target=" + e.getTargetName() + " uuid=" + e.getTarget() + " actor=" + e.getActor() + " via=" + e.getVia()
                + " added=" + e.getAdded() + " removed=" + e.getRemoved() + " confirmed=" + e.getConfirmedWarnings()
                + " whitelisted=" + e.isNewlyWhitelisted() + " main=" + Bukkit.isPrimaryThread());
    }

    @EventHandler public void on(JoinAttemptEvent e) {
        getLogger().info("SPY attempt name=" + e.getName() + " uuid=" + e.getAccount() + " count=" + e.getAttempts() + " main=" + Bukkit.isPrimaryThread());
    }

    @EventHandler public void on(UsherProblemEvent e) {
        getLogger().info("SPY problem code=" + e.getCode() + " main=" + Bukkit.isPrimaryThread() + " message=" + e.getMessage());
    }
}
