package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentTrackingService;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservationFixtures;

class RecentTrackingServiceTest extends GridTestScope {

    private final LiveHub hub = new LiveHub();
    private final StableKey grid = TestGridFixtures.key(91);
    private final StableKey iron = TestGridFixtures.key(92);

    @Test
    void saturatedAdmissionCountsRunningWorkAndPublishesUnknownWithoutAnotherSample() throws Exception {
        TerminalRegistry terminals = terminal(1);
        try (RecentTrackingService service = new RecentTrackingService(terminals, 2)) {
            service.open(grid, 1, false, true, 100, 1000);
            synchronized (hub) {
                assertTrue(service.submit(grid, 1, flow(6000, 17), false, true));
                assertFalse(
                    service.submit(grid, 1, flow(11000, 99), false, true),
                    "Both queued and currently processing observations consume admission capacity");
            }
            var unknown = awaitRecent(terminals, 11000);
            assertFalse(unknown.flowComplete());
            assertNull(
                unknown.resources()
                    .get(iron)
                    .input(),
                "Dropped flow cannot be silently reported as zero");
            assertTrue(service.submit(grid, 1, flow(141000, 3), false, true));
            var recovered = awaitRecent(terminals, 141000);
            assertTrue(recovered.flowComplete());
            assertEquals(
                "3",
                recovered.resources()
                    .get(iron)
                    .input());
        }
    }

    @Test
    void resetRejectsAlreadyQueuedResultsWithinTheSameNativeGeneration() throws Exception {
        TerminalRegistry terminals = terminal(1);
        try (RecentTrackingService service = new RecentTrackingService(terminals, 100)) {
            service.open(grid, 1, true, true, 100, 1000);
            synchronized (hub) {
                assertTrue(service.submit(grid, 1, stock(1000), true, false));
                assertTrue(service.submit(grid, 1, flow(6000, 17), false, true));
                service.invalidate(grid, 1, 7000);
            }
            var summary = awaitRecent(terminals, 7000);
            assertNull(summary.stockSince());
            assertFalse(summary.flowComplete());
            assertNull(
                summary.resources()
                    .get(iron)
                    .input());
            assertTrue(service.submit(grid, 1, stock(301000), true, false));
            summary = awaitRecent(terminals, 301000);
            assertEquals(301000, summary.stockSince());
            assertNull(
                summary.resources()
                    .get(iron)
                    .stockChange());
        }
    }

    @Test
    void retiredGenerationCannotPublishIntoAReplacementAndCloseRejectsFurtherWork() throws Exception {
        TerminalRegistry terminals = terminal(1);
        RecentTrackingService service = new RecentTrackingService(terminals, 100);
        try {
            service.open(grid, 1, false, true, 100, 1000);
            synchronized (hub) {
                assertTrue(service.submit(grid, 1, flow(6000, 17), false, true));
                service.retire(grid, 1);
                assertFalse(service.refresh(grid, 1, 7000));
                terminals.activate(grid, 2, 7000);
                terminals.observed(grid, 2, stock(7000));
                service.open(grid, 2, false, true, 100, 7000);
                assertTrue(service.refresh(grid, 2, 7000));
            }
            var summary = awaitRecent(terminals, 7000);
            assertEquals(7000, summary.flowSince());
            assertEquals(
                "0",
                summary.resources()
                    .get(iron)
                    .input());
            assertFalse(service.submit(grid, 1, flow(8000, 99), false, true));
            service.close();
            assertNull(terminals.recent(grid));
            assertFalse(service.submit(grid, 2, flow(9000, 99), false, true));
            assertFalse(service.refresh(grid, 2, 9000));
            assertThrows(IllegalStateException.class, () -> service.open(grid, 3, true, true, 100, 10000));
        } finally {
            service.close();
        }
    }

    @Test
    void detachedSnapshotIncludesAbsentResourcesWithoutTerminalAndRejectsStaleCoverage() throws Exception {
        TerminalRegistry terminals = new TerminalRegistry(hub);
        try (RecentTrackingService service = new RecentTrackingService(terminals, 100)) {
            service.open(grid, 1, false, true, 100, 1000);
            assertNull(service.snapshot(grid));
            assertTrue(service.submit(grid, 1, flow(6000, 17), false, true));
            var summary = awaitSnapshot(service, 6000);
            assertEquals(
                "17",
                summary.resources()
                    .get(iron)
                    .input());
            assertNull(terminals.recent(grid), "No terminal state or subscription is needed");
            synchronized (hub) {
                assertTrue(service.submit(grid, 1, flow(11000, 99), false, true));
                service.invalidate(grid, 1, 12000);
                var invalidated = service.snapshot(grid);
                assertTrue(invalidated == null || !invalidated.flowComplete());
                service.retire(grid, 1);
                assertNull(service.snapshot(grid));
                service.open(grid, 2, true, false, 100, 13000);
                assertTrue(service.refresh(grid, 2, 13000));
            }
            var disabled = awaitSnapshot(service, 13000);
            assertNull(disabled.flowSince());
            assertFalse(disabled.flowComplete());
            assertFalse(
                disabled.resources()
                    .containsKey(iron));
            service.close();
            assertNull(service.snapshot(grid));
        }
    }

    private RecentResourceActivity.Summary awaitSnapshot(RecentTrackingService service, long at)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var summary = service.snapshot(grid);
            if (summary != null && summary.observedAt() >= at) return summary;
            Thread.sleep(1);
        }
        throw new AssertionError("Detached recent measurements were not published");
    }

    private TerminalRegistry terminal(long generation) {
        TerminalRegistry terminals = new TerminalRegistry(hub);
        terminals.activate(grid, generation, 1000);
        terminals.observed(grid, generation, stock(1000));
        return terminals;
    }

    private StorageObservation stock(long at) {
        return StorageObservationFixtures.observation(
            at,
            true,
            true,
            Collections.singletonList(
                new StorageObservationFixtures.Row(
                    new ResourceSnapshot(
                        new ResourceDescriptor("minecraft", "iron_ingot", 0, 0, "Iron", ResourceType.ITEM, iron, iron),
                        10),
                    false)),
            Collections.emptyList());
    }

    private StorageObservation flow(long at, long input) {
        return new StorageObservation(
            at,
            false,
            true,
            false,
            Collections.emptyList(),
            Collections.singletonList(new StorageObservation.Flow(iron, input, 0)),
            Collections.emptyMap(),
            Collections.emptyList());
    }

    private @NotNull RecentResourceActivity.Summary awaitRecent(TerminalRegistry terminals, long at)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var summary = terminals.recent(grid);
            if (summary != null && summary.observedAt() >= at) return summary;
            Thread.sleep(1);
        }
        throw new AssertionError("Recent measurements did not reach the terminal");
    }
}
