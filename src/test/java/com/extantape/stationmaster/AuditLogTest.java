package com.extantape.stationmaster;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {

    @TempDir Path dir;

    static JsonObject entry(long seq, String event, String request) {
        JsonObject o = new JsonObject();
        if (seq > 0) o.addProperty("seq", seq);
        o.addProperty("event", event);
        if (request != null) o.addProperty("request", request);
        return o;
    }

    @Test
    void findsChangesCutOffByACrashAndSurvivesAHalfWrittenLine() throws Exception {
        Path file = dir.resolve("changes.log");
        AuditLog log = new AuditLog(file);
        log.write(entry(log.next(), "started", "a"));
        log.write(entry(1, "done", "a"));
        log.write(entry(log.next(), "started", "b"));
        Files.writeString(file, "{\"seq\":3,\"event\":\"sta", StandardOpenOption.APPEND); // the kill landed mid-write

        AuditLog after = new AuditLog(file);
        assertEquals(1, after.unreadable().size());
        var open = after.unfinished();
        assertEquals(1, open.size());
        assertEquals(2, open.get(0).get("seq").getAsLong());
        assertEquals(3, after.next(), "numbering carries on after the last good line");
        assertNotNull(after.resultFor("a"));
        assertNull(after.resultFor("b"));
    }

    @Test
    void onlyFinishedRequestsAreReplayed() throws Exception {
        AuditLog log = new AuditLog(dir.resolve("changes.log"));
        log.write(entry(0, "refused", "r1"));
        log.write(entry(1, "failed", "r2"));
        log.write(entry(0, "nochange", "r3"));
        assertNull(log.resultFor("r1"));
        assertNull(log.resultFor("r2"));
        assertNotNull(log.resultFor("r3"));
    }

    @Test
    void tailKeepsTheNewest() throws Exception {
        AuditLog log = new AuditLog(dir.resolve("changes.log"));
        for (int i = 1; i <= 30; i++) log.write(entry(i, "done", "x" + i));
        var tail = log.tail(5);
        assertEquals(5, tail.size());
        assertEquals("x30", tail.get(4).get("request").getAsString());
    }
}
