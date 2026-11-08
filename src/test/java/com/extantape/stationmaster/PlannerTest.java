package com.extantape.stationmaster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {

    Settings settings;
    FakeStore store;

    @BeforeEach
    void setUp() throws Exception {
        settings = Settings.parse(SettingsTest.defaults());
        store = new FakeStore().group("default").group("young", "default").group("grown", "default").group("builders", "default")
                .group("young_sky").group("grown_sky").group("admin").group("owner", "admin").group("moderator");
    }

    Planner.Plan plan(GroupStore.UserState state, Set<String> add, Set<String> remove, Set<String> confirmed) {
        return Planner.plan(settings, store, state, add, remove, confirmed);
    }

    @Test
    void addsOneProfile() {
        var p = plan(store.user("default"), Set.of("builders"), Set.of(), Set.of());
        assertFalse(p.refused());
        assertTrue(p.unconfirmed().isEmpty());
        assertEquals(Set.of("builders"), p.addGroups());
    }

    @Test
    void addsSeveralAtOnce() {
        var p = plan(store.user("default"), Set.of("young", "builders", "young_sky"), Set.of(), Set.of());
        assertTrue(p.unconfirmed().isEmpty(), String.valueOf(p.unconfirmed()));
        assertEquals(Set.of("young", "builders", "young_sky"), p.addGroups());
    }

    @Test
    void bridgingCirclesNeedsAYes() {
        var p = plan(store.user("young"), Set.of("grown"), Set.of(), Set.of());
        assertEquals(1, p.unconfirmed().size());
        assertEquals("bridge", p.unconfirmed().get(0).code());
        var yes = plan(store.user("young"), Set.of("grown"), Set.of(), Set.of("bridge"));
        assertTrue(yes.unconfirmed().isEmpty());
        assertEquals(Set.of("grown"), yes.addGroups());
    }

    @Test
    void bridgingWhenBothAreAddedAtOnce() {
        var p = plan(store.user(), Set.of("young", "grown"), Set.of(), Set.of());
        assertTrue(p.unconfirmed().stream().anyMatch(w -> w.code().equals("bridge")));
    }

    @Test
    void swappingCirclesIsNotABridge() {
        var p = plan(store.user("young"), Set.of("grown"), Set.of("young"), Set.of());
        assertTrue(p.unconfirmed().isEmpty(), String.valueOf(p.unconfirmed()));
    }

    @Test
    void alreadyBridgedPeopleAreNotAskedAgain() {
        var p = plan(store.user("young", "grown"), Set.of("builders"), Set.of(), Set.of());
        assertTrue(p.unconfirmed().isEmpty());
    }

    @Test
    void adultToggleAsksForAYesAndAYesForOneThingDoesNotCoverAnother() {
        var p = plan(store.user("grown"), Set.of("grown_sky"), Set.of(), Set.of());
        assertEquals("confirm:grown_sky", p.unconfirmed().get(0).code());
        var both = plan(store.user("young"), Set.of("grown", "grown_sky"), Set.of(), Set.of("confirm:grown_sky"));
        assertEquals(1, both.unconfirmed().size());
        assertEquals("bridge", both.unconfirmed().get(0).code());
    }

    @Test
    void unknownProfileIsRefused() {
        assertEquals("unknown_profile", plan(store.user(), Set.of("nope"), Set.of(), Set.of()).refusal());
    }

    @Test
    void addAndRemoveTogetherIsRefused() {
        assertEquals("conflict", plan(store.user(), Set.of("young"), Set.of("young"), Set.of()).refusal());
    }

    @Test
    void groupMissingFromLuckPermsIsRefused() {
        store.parents.remove("builders");
        assertEquals("restricted", plan(store.user(), Set.of("builders"), Set.of(), Set.of()).refusal());
    }

    @Test
    void aProfileThatInheritsARestrictedGroupIsRefused() {
        store.group("builders", "default", "moderator");
        var p = plan(store.user(), Set.of("builders"), Set.of(), Set.of());
        assertEquals("restricted", p.refusal());
        assertTrue(p.refusalText().contains("moderator"));
    }

    @Test
    void aProfileThatGrantsAdminPowersIsRefused() {
        store.perm("builders", "stationmaster.approve");
        assertEquals("restricted", plan(store.user(), Set.of("builders"), Set.of(), Set.of()).refusal());
        store.perms.get("builders").clear();
        store.perm("default", "*");
        assertEquals("restricted", plan(store.user(), Set.of("builders"), Set.of(), Set.of()).refusal());
    }

    @Test
    void removingAProfileTheyDoNotHaveIsHarmless() {
        var p = plan(store.user("young"), Set.of(), Set.of("builders"), Set.of());
        assertTrue(p.nothingToDo());
        assertEquals(java.util.List.of("builders"), p.didNotHave());
    }

    @Test
    void aProfileThatComesThroughAnotherGroupIsReportedNotRemoved() {
        store.group("family", "young", "builders");
        var p = plan(store.user("family"), Set.of(), Set.of("builders"), Set.of());
        assertTrue(p.nothingToDo());
        assertEquals(java.util.List.of("builders"), p.keptByInheritance());
    }

    @Test
    void addingWhatTheyHaveIsANoOp() {
        var p = plan(store.user("young"), Set.of("young"), Set.of(), Set.of());
        assertTrue(p.nothingToDo());
        assertEquals(java.util.List.of("young"), p.alreadyHad());
    }

    @Test
    void brokenSettingsRefuse() {
        settings = Settings.broken("x");
        assertEquals("config_broken", plan(store.user(), Set.of("young"), Set.of(), Set.of()).refusal());
    }

    @Test
    void worldsCloseOnlyWhenNoProfileOpensThem() {
        assertTrue(WorldAccess.closed(settings, Set.of("default"), "young_explore_nether"));
        assertFalse(WorldAccess.closed(settings, Set.of("young"), "young_explore_nether"));
        assertFalse(WorldAccess.closed(settings, Set.of("default"), "lobby"));
        assertTrue(WorldAccess.closed(settings, Set.of("young"), "workshop"));
    }
}
