package com.extantape.usher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {

    @Test
    void copiesTheOldFolderWhenTheNewOneIsMissing(@TempDir Path plugins) throws Exception {
        Path old = plugins.resolve("Stationmaster");
        Files.createDirectories(old.resolve("sub"));
        Files.writeString(old.resolve("config.yml"), "profiles: {}\n");
        Files.writeString(old.resolve("changes.log"), "{\"a\":1}\n");
        Files.writeString(old.resolve("sub/x.json"), "[]");

        String msg = Migration.copyIfMissing(old, plugins.resolve("Usher"));

        assertNotNull(msg);
        assertEquals("profiles: {}\n", Files.readString(plugins.resolve("Usher/config.yml")));
        assertEquals("{\"a\":1}\n", Files.readString(plugins.resolve("Usher/changes.log")));
        assertEquals("[]", Files.readString(plugins.resolve("Usher/sub/x.json")));
        assertTrue(Files.exists(old.resolve("config.yml")), "the old folder is left as a backup");
        assertFalse(Files.exists(plugins.resolve("Usher.migrating")));
    }

    @Test
    void neverOverwritesAnExistingNewFolder(@TempDir Path plugins) throws Exception {
        Files.createDirectories(plugins.resolve("Stationmaster"));
        Files.writeString(plugins.resolve("Stationmaster/config.yml"), "old");
        Files.createDirectories(plugins.resolve("Usher"));
        Files.writeString(plugins.resolve("Usher/config.yml"), "new");

        assertNull(Migration.copyIfMissing(plugins.resolve("Stationmaster"), plugins.resolve("Usher")));
        assertEquals("new", Files.readString(plugins.resolve("Usher/config.yml")));
    }

    @Test
    void doesNothingOnAFreshServer(@TempDir Path plugins) {
        assertNull(Migration.copyIfMissing(plugins.resolve("Stationmaster"), plugins.resolve("Usher")));
        assertFalse(Files.exists(plugins.resolve("Usher")));
    }

    @Test
    void clearsAHalfFinishedCopyFromACrash(@TempDir Path plugins) throws Exception {
        Files.createDirectories(plugins.resolve("Stationmaster"));
        Files.writeString(plugins.resolve("Stationmaster/config.yml"), "real");
        Files.createDirectories(plugins.resolve("Usher.migrating"));
        Files.writeString(plugins.resolve("Usher.migrating/junk"), "half");

        assertNotNull(Migration.copyIfMissing(plugins.resolve("Stationmaster"), plugins.resolve("Usher")));
        assertEquals("real", Files.readString(plugins.resolve("Usher/config.yml")));
        assertFalse(Files.exists(plugins.resolve("Usher/junk")));
    }
}
