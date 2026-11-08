package com.extantape.stationmaster.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired on the main thread when someone not on the whitelist tries to join. They are still turned away. Fires at most
 * once every ten minutes per account, so repeated tries can't flood a channel.
 */
public final class JoinAttemptEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID account;
    private final String name;
    private final int attempts;

    public JoinAttemptEvent(UUID account, String name, int attempts) {
        this.account = account; this.name = name; this.attempts = attempts;
    }

    public UUID getAccount() { return account; }
    public String getName() { return name; }
    /** How many times this account has tried, counted since it was first turned away. */
    public int getAttempts() { return attempts; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
