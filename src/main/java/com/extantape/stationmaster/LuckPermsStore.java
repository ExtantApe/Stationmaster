package com.extantape.stationmaster;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** LuckPerms through its API. Only global, permanent group memberships count as "direct". */
final class LuckPermsStore implements GroupStore {

    private final LuckPerms lp;

    LuckPermsStore(LuckPerms lp) { this.lp = lp; }

    @Override public boolean ready() { return true; }

    @Override
    public CompletableFuture<UserState> load(UUID id, String name) {
        return (name == null ? lp.getUserManager().loadUser(id) : lp.getUserManager().loadUser(id, name)).thenApply(LuckPermsStore::state);
    }

    @Override
    public CompletableFuture<UserState> modify(UUID id, String name, Set<String> add, Set<String> remove) {
        var users = lp.getUserManager();
        return users.savePlayerData(id, name)
                .thenCompose(r -> users.loadUser(id, name))
                .thenCompose(user -> {
                    for (InheritanceNode n : user.getNodes(NodeType.INHERITANCE)) {
                        if (n.getContexts().isEmpty() && remove.contains(n.getGroupName().toLowerCase(Locale.ROOT))) user.data().remove(n);
                    }
                    for (String g : add) user.data().add(InheritanceNode.builder(g).build());
                    return users.saveUser(user).thenApply(v -> state(user));
                })
                .handle((state, error) -> {
                    if (error == null) return CompletableFuture.completedFuture(state);
                    // Put the in-memory user back to what storage holds, so nothing is half applied.
                    CompletableFuture<UserState> failed = new CompletableFuture<>();
                    users.loadUser(id).whenComplete((u, e2) -> failed.completeExceptionally(error));
                    return failed;
                })
                .thenCompose(f -> f);
    }

    @Override
    public Set<String> lineage(String group) {
        Group g = lp.getGroupManager().getGroup(group);
        if (g == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        out.add(g.getName().toLowerCase(Locale.ROOT));
        for (Group parent : g.getInheritedGroups(QueryOptions.nonContextual())) out.add(parent.getName().toLowerCase(Locale.ROOT));
        return out;
    }

    @Override
    public boolean grants(String group, String permission) {
        Group g = lp.getGroupManager().getGroup(group);
        return g != null && g.getCachedData().getPermissionData(QueryOptions.nonContextual()).checkPermission(permission).asBoolean();
    }

    private static UserState state(User user) {
        Set<String> direct = new LinkedHashSet<>(), inherited = new LinkedHashSet<>();
        for (InheritanceNode n : user.getNodes(NodeType.INHERITANCE)) if (n.getContexts().isEmpty()) direct.add(n.getGroupName().toLowerCase(Locale.ROOT));
        for (Group g : user.getInheritedGroups(QueryOptions.nonContextual())) inherited.add(g.getName().toLowerCase(Locale.ROOT));
        inherited.addAll(direct);
        return new UserState(Set.copyOf(direct), Set.copyOf(inherited));
    }

    /** Used when LuckPerms isn't installed or didn't start. */
    static final class Missing implements GroupStore {
        @Override public boolean ready() { return false; }
        @Override public CompletableFuture<UserState> load(UUID id, String name) { return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms isn't running")); }
        @Override public CompletableFuture<UserState> modify(UUID id, String name, Set<String> a, Set<String> r) { return load(id, name); }
        @Override public Set<String> lineage(String group) { return Set.of(); }
        @Override public boolean grants(String group, String permission) { return false; }
    }
}
