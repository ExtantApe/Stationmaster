package com.extantape.stationmaster;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Decides what a change would do, before anything is touched. No side effects. */
public final class Planner {

    /** Permissions a profile's group must never carry, since they'd let the person approve or edit permissions. */
    static final List<String> ADMIN_PERMISSIONS = List.of("stationmaster.approve", "luckperms.user.parent.add", "luckperms.editor");

    public record Warning(String code, String text) {}

    /**
     * The outcome. {@code refusal} is set when nothing may happen at all. Otherwise {@code unconfirmed} lists warnings
     * not yet confirmed; when it's empty the change may go ahead.
     */
    public record Plan(String refusal, String refusalText, List<Warning> warnings, List<Warning> unconfirmed,
                       Set<String> addGroups, Set<String> removeGroups, List<String> alreadyHad, List<String> didNotHave,
                       List<String> keptByInheritance) {
        static Plan refuse(String code, String text) {
            return new Plan(code, text, List.of(), List.of(), Set.of(), Set.of(), List.of(), List.of(), List.of());
        }
        public boolean refused() { return refusal != null; }
        public boolean nothingToDo() { return addGroups.isEmpty() && removeGroups.isEmpty(); }
    }

    private Planner() {}

    public static Plan plan(Settings settings, GroupStore store, GroupStore.UserState state,
                            Set<String> addIds, Set<String> removeIds, Set<String> confirmed) {
        if (!settings.ok()) return Plan.refuse("config_broken", "Stationmaster's config is broken, so nothing was changed.");
        for (String id : addIds) if (removeIds.contains(id))
            return Plan.refuse("conflict", "You asked to add and remove " + id + " at once, so nothing was changed.");
        Set<String> all = new LinkedHashSet<>(addIds);
        all.addAll(removeIds);
        for (String id : all) {
            Profile p = settings.profiles().get(id);
            if (p == null) return Plan.refuse("unknown_profile", "There's no profile called " + id + ", so nothing was changed.");
            String why = unsafe(settings, store, p);
            if (why != null) return Plan.refuse("restricted", why);
        }

        Set<String> add = new LinkedHashSet<>(), remove = new LinkedHashSet<>();
        List<String> had = new ArrayList<>(), notHad = new ArrayList<>(), kept = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        for (String id : addIds) {
            Profile p = settings.profiles().get(id);
            if (state.direct().contains(p.group())) { had.add(id); continue; }
            add.add(p.group());
            if (p.confirm() != null && !state.inherited().contains(p.group())) warnings.add(new Warning("confirm:" + id, p.confirm()));
        }
        for (String id : removeIds) {
            Profile p = settings.profiles().get(id);
            if (!state.direct().contains(p.group())) {
                if (state.inherited().contains(p.group())) kept.add(id); else notHad.add(id);
                continue;
            }
            remove.add(p.group());
        }

        Set<String> before = circles(settings, state.inherited());
        Set<String> afterGroups = new LinkedHashSet<>(state.inherited());
        afterGroups.removeAll(remove);
        for (String g : add) afterGroups.addAll(store.lineage(g));
        Set<String> after = circles(settings, afterGroups);
        if (after.size() >= 2 && !before.containsAll(after)) {
            warnings.add(new Warning("bridge", "This puts them in the " + String.join(" and ", after)
                    + " chat circles at once, so they'd link those chats together."));
        }
        List<Warning> unconfirmed = warnings.stream().filter(w -> !confirmed.contains(w.code())).toList();
        return new Plan(null, null, List.copyOf(warnings), unconfirmed, add, remove, had, notHad, kept);
    }

    /** Why a profile may never be granted or removed by Stationmaster, or null if it's fine. */
    public static String unsafe(Settings settings, GroupStore store, Profile p) {
        if (settings.restricted().contains(p.group()))
            return "The " + p.label() + " group can only be changed from the server console by an owner, so nothing was changed.";
        Set<String> lineage = store.lineage(p.group());
        if (lineage.isEmpty()) return "The group for " + p.label() + " (" + p.group() + ") doesn't exist in LuckPerms, so nothing was changed.";
        for (String g : lineage) if (settings.restricted().contains(g))
            return p.label() + " inherits the restricted group " + g + ", so Stationmaster won't touch it. Nothing was changed.";
        if (store.grants(p.group(), "*")) return p.label() + " grants every permission, so Stationmaster won't touch it. Nothing was changed.";
        for (String perm : ADMIN_PERMISSIONS) if (store.grants(p.group(), perm))
            return p.label() + " grants " + perm + ", an admin permission, so Stationmaster won't touch it. Nothing was changed.";
        return null;
    }

    static Set<String> circles(Settings settings, Set<String> groups) {
        Set<String> out = new TreeSet<>();
        for (Profile p : settings.profiles().values()) if (p.circle() != null && groups.contains(p.group())) out.add(p.circle());
        return out;
    }
}
