package com.extantape.stationmaster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Groups with parents and permissions, in memory. */
final class FakeStore implements GroupStore {
    final Map<String, Set<String>> parents = new HashMap<>();
    final Map<String, Set<String>> perms = new HashMap<>();

    FakeStore group(String name, String... parentGroups) {
        parents.put(name, Set.of(parentGroups));
        perms.putIfAbsent(name, new HashSet<>());
        return this;
    }

    FakeStore perm(String group, String perm) { perms.get(group).add(perm); return this; }

    @Override public boolean ready() { return true; }
    @Override public CompletableFuture<UserState> load(UUID id, String name) { throw new UnsupportedOperationException(); }
    @Override public CompletableFuture<UserState> modify(UUID id, String name, Set<String> a, Set<String> r) { throw new UnsupportedOperationException(); }

    @Override
    public Set<String> lineage(String group) {
        if (!parents.containsKey(group)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        walk(group, out);
        return out;
    }

    private void walk(String g, Set<String> out) {
        if (!out.add(g)) return;
        for (String p : parents.getOrDefault(g, Set.of())) walk(p, out);
    }

    @Override
    public boolean grants(String group, String permission) {
        for (String g : lineage(group)) if (perms.getOrDefault(g, Set.of()).contains(permission)) return true;
        return false;
    }

    /** The user state for someone holding these groups directly. */
    UserState user(String... direct) {
        Set<String> inherited = new LinkedHashSet<>();
        for (String d : direct) inherited.addAll(lineage(d));
        return new UserState(Set.of(direct), inherited);
    }
}
