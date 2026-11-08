package com.extantape.stationmaster;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * changes.log: one JSON object per line, appended and flushed to disk before the step it records goes ahead.
 * A change writes {@code started} before LuckPerms is touched, then {@code done} or {@code failed}. A {@code started}
 * line with no ending means the server stopped mid-change; {@link #unfinished()} finds those after a restart.
 */
public final class AuditLog {

    private static final Gson GSON = new Gson();
    private static final int KEEP_RESULTS = 2000;

    private final Path file;
    private long seq;
    private final Map<String, JsonObject> resultsByRequest = new LinkedHashMap<>();
    private final List<String> unreadable = new ArrayList<>();

    public AuditLog(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonObject o;
            try { o = JsonParser.parseString(line).getAsJsonObject(); } catch (RuntimeException e) { unreadable.add(line); continue; }
            if (o.has("seq")) seq = Math.max(seq, o.get("seq").getAsLong());
            remember(o);
        }
    }

    /** Lines that weren't JSON, such as one cut short by a crash. */
    public List<String> unreadable() { return List.copyOf(unreadable); }

    public synchronized long next() { return ++seq; }

    public synchronized void write(JsonObject entry) throws IOException {
        if (!entry.has("time")) entry.addProperty("time", Instant.now().toString());
        byte[] bytes = (GSON.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
        remember(entry);
    }

    private void remember(JsonObject o) {
        String event = str(o, "event"), request = str(o, "request");
        if (request == null || event == null) return;
        if (event.equals("done") || event.equals("nochange")) {
            resultsByRequest.remove(request);
            resultsByRequest.put(request, o);
            if (resultsByRequest.size() > KEEP_RESULTS) resultsByRequest.remove(resultsByRequest.keySet().iterator().next());
        }
    }

    /** The entry for a request ID that already went through, so a repeated request is answered, not applied twice. Refusals and failures aren't kept: those may be retried. */
    public synchronized JsonObject resultFor(String request) {
        JsonObject o = resultsByRequest.get(request);
        return o == null ? null : o.deepCopy();
    }

    /** {@code started} entries with no ending, oldest first. */
    public synchronized List<JsonObject> unfinished() throws IOException {
        Map<Long, JsonObject> open = new LinkedHashMap<>();
        if (!Files.exists(file)) return List.of();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            JsonObject o;
            try { o = JsonParser.parseString(line).getAsJsonObject(); } catch (RuntimeException e) { continue; }
            if (!o.has("seq")) continue;
            long s = o.get("seq").getAsLong();
            if ("started".equals(str(o, "event"))) open.put(s, o); else open.remove(s);
        }
        return new ArrayList<>(open.values());
    }

    /** The last {@code count} entries, newest last. */
    public synchronized List<JsonObject> tail(int count) throws IOException {
        Deque<JsonObject> out = new ArrayDeque<>();
        if (!Files.exists(file)) return List.of();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            try { out.addLast(JsonParser.parseString(line).getAsJsonObject()); } catch (RuntimeException e) { continue; }
            if (out.size() > count) out.removeFirst();
        }
        return new ArrayList<>(out);
    }

    static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
