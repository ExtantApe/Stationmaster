package com.extantape.stationmaster;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Turns a typed name into an account, in any capitalization, even if they've never joined. Players the server already
 * knows are found locally. Otherwise, in Mojang mode it asks Mojang's public profile lookup, which tells a missing
 * account (404) apart from one it couldn't reach.
 */
public final class NameResolver {

    public record Resolved(UUID id, String name) {}

    /** A name problem, with a sentence that's safe to show an admin. */
    public static final class NameProblem extends RuntimeException {
        public final String code;
        NameProblem(String code, String message) { super(message, null, false, false); this.code = code; }
    }

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{1,16}");
    static final String MOJANG = System.getProperty("stationmaster.mojang-url", "https://api.mojang.com/users/profiles/minecraft/");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Executor async;

    public NameResolver(Executor async) { this.async = async; }

    public static boolean possibleName(String name) { return name != null && VALID.matcher(name).matches(); }

    public CompletableFuture<Resolved> resolve(String typed, Settings.Lookup lookup) {
        if (!possibleName(typed)) return CompletableFuture.failedFuture(new NameProblem("bad_name",
                "\"" + typed + "\" can't be a Minecraft name. Names are 1 to 16 letters, digits or underscores."));
        return CompletableFuture.supplyAsync(() -> {
            OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(typed);
            if (known != null && known.getName() != null && (known.hasPlayedBefore() || known.isOnline() || known.isWhitelisted()))
                return new Resolved(known.getUniqueId(), known.getName());
            boolean mojang = lookup == Settings.Lookup.MOJANG || (lookup == Settings.Lookup.AUTO && Bukkit.getOnlineMode());
            return mojang ? askMojang(typed) : offline(typed);
        }, async);
    }

    static Resolved offline(String name) {
        return new Resolved(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
    }

    private Resolved askMojang(String typed) {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(MOJANG + typed)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Stationmaster").GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new NameProblem("lookup_failed", "Couldn't reach Mojang to look up " + typed + ". Nothing was changed. Try again in a minute.");
        }
        if (response.statusCode() == 404 || response.statusCode() == 204)
            throw new NameProblem("no_such_account", "No Minecraft account is called " + typed + ". Check the spelling.");
        if (response.statusCode() != 200)
            throw new NameProblem("lookup_failed", "Mojang didn't answer properly (" + response.statusCode() + ") for " + typed + ". Nothing was changed. Try again in a minute.");
        try {
            JsonObject o = JsonParser.parseString(response.body()).getAsJsonObject();
            return new Resolved(parseUndashed(o.get("id").getAsString()), o.get("name").getAsString());
        } catch (RuntimeException e) {
            throw new NameProblem("lookup_failed", "Mojang's answer for " + typed + " couldn't be read. Nothing was changed.");
        }
    }

    static UUID parseUndashed(String hex) {
        if (hex.contains("-")) return UUID.fromString(hex);
        return UUID.fromString(hex.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})", "$1-$2-$3-$4-$5"));
    }
}
