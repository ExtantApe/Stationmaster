package com.extantape.usher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Usher's checks on Latchkey invites, without a server. The reflection wiring is covered by the bot tests. */
class LatchkeyHookTest {
    Settings settings;
    FakeStore store;

    @BeforeEach void setUp() throws Exception {
        settings = Settings.parse(SettingsTest.defaults());
        store = new FakeStore().group("default").group("young", "default").group("grown", "default").group("builders", "default")
                .group("young_sky").group("grown_sky").group("admin").group("owner", "admin").group("moderator");
    }

    @Test void aPlainProfileIsFine() {
        var c = LatchkeyHook.check(settings, store, List.of("builders"), Set.of());
        assertFalse(c.refused());
        assertTrue(c.questions().isEmpty());
    }

    @Test void anUnknownProfileIsRefusedWithACommandFriendlyCode() {
        var c = LatchkeyHook.check(settings, store, List.of("wizards"), Set.of());
        assertEquals("unknown-profile", c.refusalCode());
        assertTrue(c.refusalText().contains("wizards"));
    }

    @Test void aProfileThatGrantsAdminIsRefused() {
        store.perm("builders", "*");
        var c = LatchkeyHook.check(settings, store, List.of("builders"), Set.of());
        assertEquals("restricted", c.refusalCode());
    }

    @Test void twoCirclesAtOnceIsAQuestionNotARefusal() {
        var c = LatchkeyHook.check(settings, store, List.of("young", "grown"), Set.of());
        assertFalse(c.refused());
        assertTrue(c.questions().containsKey("bridge"), String.valueOf(c.questions()));
    }

    @Test void everyQuestionIdFitsTheCommandFormat() {
        var c = LatchkeyHook.check(settings, store, List.of("young", "grown", "young_sky", "grown_sky", "builders"), Set.of());
        for (String id : c.questions().keySet()) assertTrue(id.matches("[a-z0-9-]{1,64}"), id);
    }

    @Test void yesIdsMapBackToUshersCodes() {
        assertEquals("confirm-grown-sky", LatchkeyHook.toYesId("confirm:grown_sky"));
        assertEquals(Set.of("confirm:grown_sky", "bridge"), LatchkeyHook.confirmedCodes(settings, Set.of("confirm-grown-sky", "bridge")));
        assertEquals(Set.of(), LatchkeyHook.confirmedCodes(settings, Set.of("confirm-nothing", "made-up")));
    }

    @Test void aQuestionIsAnsweredOnlyWithItsOwnYes() {
        var asked = LatchkeyHook.check(settings, store, List.of("grown_sky"), Set.of());
        assertTrue(asked.questions().containsKey("confirm-grown-sky"), String.valueOf(asked.questions()));
        var answered = LatchkeyHook.check(settings, store, List.of("grown_sky"), Set.of("confirm-grown-sky"));
        assertEquals(asked.questions().keySet(), answered.questions().keySet(), "every question is still listed; Latchkey drops the answered ones");
    }

    @Test void withoutLuckPermsNothingIsAllowed() {
        GroupStore down = new GroupStore() {
            public boolean ready() { return false; }
            public java.util.concurrent.CompletableFuture<UserState> load(java.util.UUID id, String n) { throw new UnsupportedOperationException(); }
            public java.util.concurrent.CompletableFuture<UserState> modify(java.util.UUID id, String n, Set<String> a, Set<String> r) { throw new UnsupportedOperationException(); }
            public Set<String> lineage(String g) { return store.lineage(g); }
            public boolean grants(String g, String p) { return store.grants(g, p); }
        };
        assertEquals("luckperms-missing", LatchkeyHook.check(settings, down, List.of("builders"), Set.of()).refusalCode());
    }
}
