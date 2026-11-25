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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        try (PatternProviderCoordinator views = new PatternProviderCoordinator(
            new LiveHub(),
            k -> grid,
            () -> true,
            () -> 0L)) {
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
    }

    private enum Retry {
        UNCHANGED,
        REVISION_CHANGED,
        GRACE_EXPIRED,
        CANCELLED
    }

    @ParameterizedTest
    @EnumSource(Retry.class)
    void onlyAFreshRetryOfTheSameRevisionResumesAnUnfinishedBaseline(Retry retry) throws Exception {
        StableKey key = StableKey.random();
        TestPatternProvider provider = new TestPatternProvider();
        AtomicInteger enumerations = new AtomicInteger();
        AtomicInteger revisionChecks = new AtomicInteger();
        AtomicLong revision = new AtomicLong(1);
        AtomicLong nanos = new AtomicLong();
        IAEGrid grid = new TestGridFixtures.TestGrid(2, false, CONTROLLER_ONLINE) {

            @Override
            public @NotNull Iterable<? extends IPatternProvider> web$getPatternProviders() {
                enumerations.incrementAndGet();
                return Collections.singletonList(provider);
            }

            @Override
            public long web$getPatternRevision() {
                revisionChecks.incrementAndGet();
                return revision.get();
            }
        };
        try (PatternProviderCoordinator views = new PatternProviderCoordinator(
            new LiveHub(),
            ignored -> grid,
            () -> true,
            () -> nanos.addAndGet(MILLISECONDS.toNanos(50)))) {
            long now = 0;
            try (PatternProviderRegistry.Opening first = views.prepare(key, grid, now)) {
                assertNotNull(first);
                // Each native read consumes a tick here. Time out after part of the provider was captured.
                while (provider.slotReads == 0 && now < 1000) views.tick(++now);
                assertTrue(provider.slotReads > 0);
                assertFalse(first.awaitReady(0, MILLISECONDS));
                if (retry != Retry.CANCELLED) first.closeForRetry(now);
            }
            int pausedAt = provider.slotReads;
            int initialChecks = revisionChecks.get();
            if (retry == Retry.REVISION_CHANGED) {
                provider.name = "Updated during pause";
                revision.incrementAndGet();
            }
            long retryAt = now + (retry == Retry.GRACE_EXPIRED ? 600_000 : 100);
            views.tick(now + 1);
            views.tick(retryAt - 1);
            assertEquals(pausedAt, provider.slotReads, "A paused baseline must not read slots without demand");
            assertEquals(initialChecks, revisionChecks.get(), "A paused baseline must not poll the grid");
            try (PatternProviderRegistry.Opening reopened = views.prepare(key, grid, retryAt)) {
                assertNotNull(reopened);
                for (int i = 0; i < 200 && !reopened.awaitReady(0, MILLISECONDS); i++) views.tick(retryAt + i);
                assertTrue(reopened.awaitReady(0, MILLISECONDS));
                PatternProviderRegistry.Bootstrap snapshot = views.registry()
                    .snapshot(reopened, null, false, false, null, null, retryAt + 200);
                assertNotNull(snapshot);
                assertEquals(
                    provider.name,
                    snapshot.providers()
                        .get(0)
                        .header()
                        .name());
                assertEquals(
                    provider.web$getPatternSlotCount(),
                    snapshot.providers()
                        .get(0)
                        .slots()
                        .size());
                int expectedReads = provider.web$getPatternSlotCount() + (retry == Retry.UNCHANGED ? 0 : pausedAt);
                assertEquals(expectedReads, provider.slotReads, "Only the unchanged timeout may reuse captured slots");
                assertEquals(retry == Retry.UNCHANGED ? 1 : 2, enumerations.get());
            }
        }
    }

    @Test
    void unavailableNetworksCannotServeSnapshots() throws Exception {
        StableKey key = StableKey.random();
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(1, false, CONTROLLER_ONLINE);
        LiveHub hub = new LiveHub();
        try (PatternProviderCoordinator disabled = new PatternProviderCoordinator(
            hub,
            k -> grid,
            () -> false,
            () -> 0L)) {
            assertNull(disabled.prepare(key, grid, 0));
        }
        try (PatternProviderCoordinator views = new PatternProviderCoordinator(hub, k -> grid, () -> true, () -> 0L);
            PatternProviderRegistry.Opening opening = views.prepare(key, grid, 0)) {
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
