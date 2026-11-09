package com.extantape.usher.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Fired on the main thread after a change is saved: someone was approved, or their profiles changed. Listen to it to
 * post to Discord or anywhere else. Usher itself sends nothing over the network.
 */
public final class ProfilesChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID target;
    private final String targetName;
    private final String actor;
    private final String via;
    private final @Nullable String requestId;
    private final List<String> added;
    private final List<String> removed;
    private final List<String> confirmedWarnings;
    private final boolean newlyWhitelisted;

    public ProfilesChangedEvent(UUID target, String targetName, String actor, String via, @Nullable String requestId,
                                List<String> added, List<String> removed, List<String> confirmedWarnings, boolean newlyWhitelisted) {
        this.target = target; this.targetName = targetName; this.actor = actor; this.via = via; this.requestId = requestId;
        this.added = List.copyOf(added); this.removed = List.copyOf(removed);
        this.confirmedWarnings = List.copyOf(confirmedWarnings); this.newlyWhitelisted = newlyWhitelisted;
    }

    public UUID getTarget() { return target; }
    public String getTargetName() { return targetName; }
    /** The admin's name, or "Console". */
    public String getActor() { return actor; }
    /** How the change was made: menu, console or leaf-juice. */
    public String getVia() { return via; }
    public @Nullable String getRequestId() { return requestId; }
    /** Profile ids added. */
    public List<String> getAdded() { return added; }
    /** Profile ids removed. */
    public List<String> getRemoved() { return removed; }
    /** Warning codes the admin said yes to, such as bridge or confirm:&lt;profile&gt;. */
    public List<String> getConfirmedWarnings() { return confirmedWarnings; }
    /** True when this change added them to the whitelist. */
    public boolean isNewlyWhitelisted() { return newlyWhitelisted; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
