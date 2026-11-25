package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_ONLINE;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.NO_CONTROLLER;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderCoordinator;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderRegistry;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderTopic;

class PatternProviderLiveTest {

    @Test
    void openingReturnsAllSlotsAndOnlyAViewerTriggersFurtherReads() {
        TestPatternProvider provider = new TestPatternProvider();
        AtomicInteger enumerations = new AtomicInteger();
        AtomicInteger revisions = new AtomicInteger();
        AtomicLong revision = new AtomicLong();
        StableKey key = StableKey.random();
        IAEGrid grid = new TestGridFixtures.TestGrid(1, false, CONTROLLER_ONLINE) {

            @Override
            public @NotNull Iterable<? extends IPatternProvider> web$getPatternProviders() {
                enumerations.incrementAndGet();
                return Collections.singletonList(provider);
            }

            @Override
            public long web$getPatternRevision() {
                revisions.incrementAndGet();
                return revision.get();
            }
        };
        try (PatternProviderCoordinator views = new PatternProviderCoordinator(new LiveHub(), k -> grid, () -> true)) {
            views.tick(0);
            assertEquals(0, enumerations.get());
            PatternProviderTopic topic = views.prepare(key, grid, 1);
            assertNotNull(topic);
            views.tick(1); // Before HTTP reserves its completed snapshot.
            AuthSession viewer = new AuthSession(TestGridFixtures.principal(8), Long.MAX_VALUE);
            PatternProviderRegistry.Bootstrap baseline = views.registry()
                .snapshot(topic, viewer, true, false, null, null, 1);
            assertNotNull(baseline);
            assertEquals(
                provider.web$getPatternSlotCount(),
                baseline.providers()
                    .get(0)
                    .slots()
                    .size());
            assertEquals(1, enumerations.get());
            int checked = revisions.get();
            revision.incrementAndGet();
            views.tick(2);
            assertEquals(checked, revisions.get());
            views.tick(1001);
            assertEquals(2, enumerations.get());
            views.tick(2001);
            assertEquals(2, enumerations.get());
            assertNotNull(baseline.subscription());
            views.registry()
                .cancel(baseline.subscription());
            views.tick(3100);
            assertEquals(2, enumerations.get());
            PatternProviderTopic reopened = views.prepare(key, grid, 32001);
            assertNotNull(reopened);
            assertNotEquals(topic.generation(), reopened.generation());
            assertNull(
                views.registry()
                    .snapshot(topic, null, false, false, null, null, 32001));
        }
    }

    @Test
    void unavailableNetworksCannotServeSnapshots() {
        StableKey key = StableKey.random();
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(1, false, CONTROLLER_ONLINE);
        LiveHub hub = new LiveHub();
        try (PatternProviderCoordinator disabled = new PatternProviderCoordinator(hub, k -> grid, () -> false)) {
            assertNull(disabled.prepare(key, grid, 0));
        }
        try (PatternProviderCoordinator views = new PatternProviderCoordinator(hub, k -> grid, () -> true)) {
            grid.controllerState(NO_CONTROLLER);
            assertNull(views.prepare(key, grid, 0));
        }
    }
}
