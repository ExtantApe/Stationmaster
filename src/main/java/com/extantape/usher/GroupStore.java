package com.extantape.usher;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The permissions side, so the rules can be tested without LuckPerms. Group names are lowercase. */
public interface GroupStore {

    /** Groups a person has directly, and every group they inherit (direct ones included). */
    record UserState(Set<String> direct, Set<String> inherited) {}

    /** False when LuckPerms isn't running. Nothing may be changed then. */
    boolean ready();

    CompletableFuture<UserState> load(UUID id, String name);

    /** Adds and removes direct groups and saves. Fails as a whole if the save fails, with the in-memory user reloaded. */
    CompletableFuture<UserState> modify(UUID id, String name, Set<String> add, Set<String> remove);

    /** The group and every group it inherits from. Empty if the group doesn't exist. */
    Set<String> lineage(String group);

    /** Whether the group, through anything it inherits, grants this permission. */
    boolean grants(String group, String permission);
}
