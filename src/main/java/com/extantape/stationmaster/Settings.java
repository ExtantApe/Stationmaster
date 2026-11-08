package com.extantape.stationmaster;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Settings read from config.yml. A config that can't be read gives {@link #broken()} settings, which refuse every change.
 * Problems with single profiles leave that profile out and are listed in {@link #problems()}.
 */
public record Settings(boolean ok, String lobbyWorld, Lookup lookup, Set<String> restricted,
                       Map<String, Profile> profiles, List<String> problems) {

    public enum Lookup { AUTO, MOJANG, OFFLINE }

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern GROUP = Pattern.compile("[a-z0-9_.-]{1,36}");

    public static Settings broken(String why) {
        return new Settings(false, "", Lookup.AUTO, Set.of(), Map.of(), List.of(why));
    }

    public static Settings parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            String first = String.valueOf(e.getMessage()).lines().findFirst().orElse("unreadable YAML");
            return broken("config.yml can't be read: " + first);
        }
        return from(config);
    }

    public static Settings from(ConfigurationSection config) {
        List<String> problems = new ArrayList<>();
        String lobby = config.getString("lobby-world", "");
        Lookup lookup;
        try {
            lookup = Lookup.valueOf(config.getString("name-lookup", "auto").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return broken("name-lookup must be auto, mojang or offline, not \"" + config.getString("name-lookup") + "\"");
        }
        if (!config.isList("restricted-groups")) return broken("restricted-groups is missing or isn't a list");
        Set<String> restricted = new LinkedHashSet<>();
        for (String g : config.getStringList("restricted-groups")) restricted.add(g.trim().toLowerCase(Locale.ROOT));

        Map<String, Profile> profiles = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("profiles");
        if (section == null && config.contains("profiles")) return broken("profiles must be a section of named profiles");
        Set<String> groups = new LinkedHashSet<>();
        if (section != null) for (String key : section.getKeys(false)) {
            String id = key.toLowerCase(Locale.ROOT);
            ConfigurationSection p = section.getConfigurationSection(key);
            if (p == null) { problems.add("Profile " + key + " isn't a section, so it's left out."); continue; }
            if (!ID.matcher(id).matches()) { problems.add("Profile " + key + " needs an id of letters, digits, - or _, so it's left out."); continue; }
            String group = p.getString("group", "").trim().toLowerCase(Locale.ROOT);
            if (!GROUP.matcher(group).matches()) { problems.add("Profile " + id + " has no usable group, so it's left out."); continue; }
            if (restricted.contains(group)) { problems.add("Profile " + id + " uses the restricted group " + group + ", so it's refused."); continue; }
            if (!groups.add(group)) { problems.add("Profile " + id + " uses the group " + group + " a second time, so it's left out."); continue; }
            List<String> worlds = p.isList("worlds") ? p.getStringList("worlds") : List.of();
            if (p.contains("worlds") && !p.isList("worlds")) problems.add("Profile " + id + ": worlds should be a list. It opens none for now.");
            List<String> description = p.isList("description") ? p.getStringList("description")
                    : p.isString("description") ? List.of(p.getString("description")) : List.of();
            String circle = blankToNull(p.getString("circle"));
            profiles.put(id, new Profile(id, p.getString("label", key), group, p.getString("icon", "PAPER"),
                    p.getBoolean("toggle", false), List.copyOf(worlds),
                    circle == null ? null : circle.toLowerCase(Locale.ROOT),
                    blankToNull(p.getString("confirm")), blankToNull(p.getString("granted")), List.copyOf(description)));
        }
        long main = profiles.values().stream().filter(p -> !p.toggle()).count(), extras = profiles.size() - main;
        if (main > 14) problems.add((main - 14) + " main profiles don't fit in the menu, which shows 14. They still work from the console.");
        if (extras > 14) problems.add((extras - 14) + " extras don't fit in the menu, which shows 14. They still work from the console.");
        return new Settings(true, lobby == null ? "" : lobby.trim(), lookup, Collections.unmodifiableSet(restricted),
                Collections.unmodifiableMap(profiles), List.copyOf(problems));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    public Profile byGroup(String group) {
        for (Profile p : profiles.values()) if (p.group().equals(group)) return p;
        return null;
    }
}
