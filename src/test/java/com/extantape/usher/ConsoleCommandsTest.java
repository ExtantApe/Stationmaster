package com.extantape.usher;

import com.extantape.signalbox.Request;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The console commands' argument rules and answer shape, without a server. The commands themselves are bot-tested. */
class ConsoleCommandsTest {
    static final String U = "00000000-0000-0000-0000-00000000000a";

    static ConsoleCommands.ApplyArgs apply(String line) throws Exception {
        return ConsoleCommands.parseApply(Request.parse(line.split(" ")).tokens());
    }

    @Test void applyReadsAddsRemovesAndDefaults() throws Exception {
        var a = apply(U + " Alex builders,young none");
        assertEquals(UUID.fromString(U), a.uuid());
        assertEquals("Alex", a.name());
        assertEquals(Set.of("builders", "young"), a.add());
        assertEquals(Set.of(), a.remove());
        assertTrue(a.whitelist(), "whitelists by default, as before");
        assertEquals("console", a.via());
        var b = apply(U + " Alex none grown_sky false leaf-juice");
        assertEquals(Set.of("grown_sky"), b.remove());
        assertFalse(b.whitelist());
        assertEquals("leaf-juice", b.via());
    }

    @Test void applyRefusesWhatItCantRead() {
        for (String bad : List.of(U + " Alex", U + " Al\"ex none none", "not-a-uuid Alex none none", U + " Alex Bad! none",
                U + " Alex none none maybe", U + " Alex none none true LEAF", U + " Alex none none true via extra")) {
            assertThrows(Exception.class, () -> apply(bad), bad);
        }
    }

    @Test void theOldPlusAndMinusFormIsRefusedByTheFormatItself() {
        Request.Bad no = assertThrows(Request.Bad.class, () -> Request.parse((U + " Alex -young --json k1").split(" ")));
        assertEquals("k1", no.id, "the refusal can still be matched to its request");
    }

    @Test void resultsBecomePlainDataSignalboxCanWrite() {
        Map<String, Object> m = ConsoleCommands.toMap(JsonParser.parseString(
            "{\"status\":\"ok\",\"added\":[\"young\"],\"count\":3,\"ratio\":0.5,\"nested\":{\"x\":true},\"none\":null}").getAsJsonObject());
        assertEquals(List.of("young"), m.get("added"));
        assertEquals(3L, m.get("count"));
        assertEquals(0.5, m.get("ratio"));
        assertEquals(Map.of("x", true), m.get("nested"));
        assertTrue(m.containsKey("none") && m.get("none") == null);
    }

    @Test void describeMarksTheOptionalArgumentsAndOnlyThose() {
        String line = com.extantape.signalbox.Answer.ok("d1", com.extantape.signalbox.Describe.body("Usher", "0.4.0", ConsoleCommands.described()));
        assertTrue(line.contains("{\"name\":\"whitelist\",\"type\":\"bool\",\"optional\":true}"), line);
        assertTrue(line.contains("{\"name\":\"via\",\"type\":\"string\",\"optional\":true}"), line);
        assertTrue(line.contains("{\"name\":\"uuid\",\"type\":\"uuid\"}"), "required ones carry no optional key");
        assertTrue(line.contains("{\"name\":\"limit\",\"type\":\"int\",\"optional\":true}"));
    }

    @Test void cursorsAreOnlyOnesUsherHandedOut() throws Exception {
        assertEquals(50, ConsoleCommands.cursorOf("50"));
        assertEquals(0, ConsoleCommands.cursorOf("0"));
        for (String bad : List.of("-1", "abc", "1.5", "9999999999")) assertThrows(ConsoleCommands.Refusal.class, () -> ConsoleCommands.cursorOf(bad), bad);
    }

    @Test void describeListsEveryCommand() {
        var names = ConsoleCommands.described().stream().map(d -> d.name()).toList();
        assertEquals(ConsoleCommands.SUBS, names);
    }
}
