package pl.kuba6000.ae2webintegration.core;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_ONLINE;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.NO_CONTROLLER;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderCoordinator;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderRegistry;

class PatternProviderLiveTest {

    @Test
    void anOpeningWaitsForTheBaselineAndOnlyKeepsReadingWhileThereIsDemand() throws Exception {
        AtomicInteger enumerations = new AtomicInteger();
        AtomicInteger revisions = new AtomicInteger();
        AtomicLong revision = new AtomicLong();
        StableKey key = StableKey.random();
        IAEGrid grid = new TestGridFixtures.TestGrid(1, false, CONTROLLER_ONLINE) {

            @Override
            public @NotNull Iterable<? extends IPatternProvider> web$getPatternProviders() {
                enumerations.incrementAndGet();
                return Collections.emptyList();
            }

            @Override
            public long web$getPatternRevision() {
                revisions.incrementAndGet();
                return revision.get();
            }
        };
        PatternProviderCoordinator views = new PatternProviderCoordinator(
            new LiveHub(),
            k -> grid,
            () -> true,
            () -> 0L);
        views.tick(0);
        assertEquals(0, enumerations.get());
        try (PatternProviderRegistry.Opening opening = views.prepare(key, grid, 1)) {
            assertNotNull(opening);
            assertFalse(opening.awaitReady(0, MILLISECONDS));
            views.tick(1);
            assertTrue(opening.awaitReady(0, MILLISECONDS));
            assertNotNull(
                views.registry()
                    .snapshot(opening, null, false, false, null, null, 2));
            int checked = revisions.get();
            revision.incrementAndGet();
            views.tick(2);
            assertEquals(checked, revisions.get());
            views.tick(1001);
            assertEquals(2, enumerations.get());
            views.tick(2001);
            assertEquals(2, enumerations.get());
            views.tick(31001);
            assertEquals(3, enumerations.get());
        }
        views.tick(32001);
        assertEquals(3, enumerations.get());
    }

    @Test
    void unavailableNetworksCannotServeSnapshots() throws Exception {
        StableKey key = StableKey.random();
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(1, false, CONTROLLER_ONLINE);
        LiveHub hub = new LiveHub();
        PatternProviderCoordinator disabled = new PatternProviderCoordinator(hub, k -> grid, () -> false, () -> 0L);
        assertNull(disabled.prepare(key, grid, 0));
        PatternProviderCoordinator views = new PatternProviderCoordinator(hub, k -> grid, () -> true, () -> 0L);
        try (PatternProviderRegistry.Opening opening = views.prepare(key, grid, 0)) {
            assertNotNull(opening);
            grid.controllerState(NO_CONTROLLER);
            views.tick(0);
            assertFalse(opening.awaitReady(0, MILLISECONDS));
            assertNull(
                views.registry()
                    .snapshot(opening, null, false, false, null, null, 0));
        }
    }
}
