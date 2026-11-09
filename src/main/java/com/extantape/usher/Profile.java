package com.extantape.usher;

import java.util.List;

/**
 * One choosable profile or toggle from config. Choosing it adds the person to one LuckPerms group.
 *
 * @param id        the config key, lowercase, used in console commands
 * @param label     what admins see
 * @param group     the LuckPerms group, lowercase
 * @param icon      the material name shown in the menu
 * @param toggle    true for an extra toggle rather than a main profile; only changes where the menu shows it
 * @param worlds    worlds this profile opens, used to send people home when it's removed; {@code *} matches any run of characters
 * @param circle    the chat circle this profile puts people in, or null
 * @param confirm   text that must be confirmed before adding it, or null
 * @param granted   the friendly line the player sees when it's added, or null
 * @param description lines shown under the label in the menu
 */
public record Profile(String id, String label, String group, String icon, boolean toggle, List<String> worlds,
                      String circle, String confirm, String granted, List<String> description) {

    public boolean opens(String world) {
        for (String pattern : worlds) if (Glob.matches(pattern, world)) return true;
        return false;
    }

    static final class Glob {
        static boolean matches(String pattern, String text) {
            String p = pattern.toLowerCase(java.util.Locale.ROOT), t = text.toLowerCase(java.util.Locale.ROOT);
            if (!p.contains("*")) return p.equals(t);
            String[] parts = p.split("\\*", -1);
            if (!t.startsWith(parts[0])) return false;
            int at = parts[0].length();
            for (int i = 1; i < parts.length - 1; i++) {
                int found = t.indexOf(parts[i], at);
                if (found < 0) return false;
                at = found + parts[i].length();
            }
            String last = parts[parts.length - 1];
            return t.length() - at >= last.length() && t.endsWith(last);
        }
    }
}
