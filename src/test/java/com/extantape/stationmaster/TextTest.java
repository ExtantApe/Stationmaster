package com.extantape.stationmaster;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TextTest {

    @Test
    void joinsListsLikeASentence() {
        assertEquals("", ChangeService.join(List.of()));
        assertEquals("A", ChangeService.join(List.of("A")));
        assertEquals("A and B", ChangeService.join(List.of("A", "B")));
        assertEquals("A, B and C", ChangeService.join(List.of("A", "B", "C")));
    }

    @Test
    void wrapsMenuText() {
        for (String line : PickerMenus.wrap("This puts them in the grown and young chat circles at once, so they'd link those chats together.", 34))
            assertTrue(line.length() <= 34, line);
    }

    @Test
    void namesAndIds() {
        assertTrue(NameResolver.possibleName("Some_Name1"));
        assertFalse(NameResolver.possibleName("has space"));
        assertFalse(NameResolver.possibleName("waytoolongforaminecraftname"));
        assertFalse(NameResolver.possibleName("../etc"));
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), NameResolver.parseUndashed("069a79f444e94726a5befca90e38aaf5"));
        // Offline-mode accounts match what the server itself computes for a name.
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:Alex".getBytes(java.nio.charset.StandardCharsets.UTF_8)), NameResolver.offline("Alex").id());
    }
}
