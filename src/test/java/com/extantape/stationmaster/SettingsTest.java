package com.extantape.stationmaster;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {

    static String defaults() throws Exception {
        return Files.readString(Path.of("src/main/resources/config.yml"));
    }

    @Test
    void theShippedConfigLoadsCleanly() throws Exception {
        Settings s = Settings.parse(defaults());
        assertTrue(s.ok(), String.valueOf(s.problems()));
        assertEquals(java.util.List.of("young", "grown", "builders", "young_magic", "grown_magic"), s.profiles().keySet().stream().toList());
        assertTrue(s.problems().isEmpty(), String.valueOf(s.problems()));
        assertNotNull(s.profiles().get("grown_magic").confirm());
        assertTrue(s.profiles().get("young_magic").toggle());
    }

    @Test
    void brokenYamlRefusesEverything() {
        Settings s = Settings.parse("profiles:\n  a: [unclosed\n");
        assertFalse(s.ok());
        assertTrue(s.problems().get(0).startsWith("config.yml can't be read"), s.problems().get(0));
        assertTrue(s.profiles().isEmpty());
    }

    @Test
    void missingRestrictedListIsBroken() {
        Settings s = Settings.parse("profiles: {}\n");
        assertFalse(s.ok());
        assertTrue(s.problems().get(0).contains("restricted-groups"));
    }

    @Test
    void badLookupIsBroken() {
        Settings s = Settings.parse("name-lookup: maybe\nrestricted-groups: []\n");
        assertFalse(s.ok());
    }

    @Test
    void restrictedGroupsAreRefusedEvenWithOtherCapitals() {
        Settings s = Settings.parse("""
                restricted-groups: [Admin]
                profiles:
                  sneaky: {label: Sneaky, group: ADMIN}
                  fine: {label: Fine, group: fine}
                """);
        assertTrue(s.ok());
        assertFalse(s.profiles().containsKey("sneaky"));
        assertTrue(s.profiles().containsKey("fine"));
        assertTrue(s.problems().stream().anyMatch(p -> p.contains("sneaky") && p.contains("restricted")), String.valueOf(s.problems()));
    }

    @Test
    void profilesWithoutGroupsOrWithRepeatsAreLeftOut() {
        Settings s = Settings.parse("""
                restricted-groups: []
                profiles:
                  nogroup: {label: X}
                  one: {group: same}
                  two: {group: same}
                  "bad id!": {group: other}
                """);
        assertEquals(java.util.Set.of("one"), s.profiles().keySet());
        assertEquals(3, s.problems().size(), String.valueOf(s.problems()));
    }

    @Test
    void worldPatterns() {
        Profile p = Settings.parse("""
                restricted-groups: []
                profiles:
                  a: {group: a, worlds: ["explore*", build, "*_magic"]}
                """).profiles().get("a");
        assertTrue(p.opens("explore"));
        assertTrue(p.opens("explore_nether"));
        assertTrue(p.opens("Build"));
        assertTrue(p.opens("big_magic"));
        assertFalse(p.opens("builder"));
        assertFalse(p.opens("lobby"));
    }

    @Test
    void tooManyProfilesForTheMenuIsSaidOutLoud() {
        StringBuilder y = new StringBuilder("restricted-groups: []\nprofiles:\n");
        for (int i = 0; i < 16; i++) y.append("  p").append(i).append(": {group: g").append(i).append("}\n");
        Settings s = Settings.parse(y.toString());
        assertEquals(16, s.profiles().size(), "they still load");
        assertTrue(s.problems().stream().anyMatch(p -> p.startsWith("2 main profiles don't fit")), String.valueOf(s.problems()));
    }
}
