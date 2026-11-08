package com.extantape.stationmaster.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Fired on the main thread when something went wrong or was refused for safety: LuckPerms missing or failing, a broken
 * config, a restricted group, self-approval, or a change cut off by a crash.
 */
public final class StationmasterProblemEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String code;
    private final String message;
    private final @Nullable UUID target;
    private final @Nullable String actor;

    public StationmasterProblemEvent(String code, String message, @Nullable UUID target, @Nullable String actor) {
        this.code = code; this.message = message; this.target = target; this.actor = actor;
    }

    /** A short code such as luckperms_missing, luckperms_failed, config_broken, config_problem, restricted, self or interrupted. */
    public String getCode() { return code; }
    /** A plain sentence for people. */
    public String getMessage() { return message; }
    public @Nullable UUID getTarget() { return target; }
    public @Nullable String getActor() { return actor; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
