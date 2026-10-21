package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.Range;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

class StorageTrackingRecoveryTest {

    @TempDir
    Path directory;

    @Test
    void sameGenerationStockRecoveryRestoresKnownAmountsAndFencesAnUncertainNewResource() throws Exception {
        StableKey grid = StableKey.random();
        StableKey unchanged = StableKey.random();
        StableKey removed = StableKey.random();
        StableKey uncertain = StableKey.random();
        long start = System.currentTimeMillis() - 10000;
        try (HsqlStorageHistoryStore stored = store()) {
            WorldHistoryFixtures.register(stored, grid);
            IStorageHistoryStore failing = failAfterCommit(stored, start + 1000);
            StorageTrackingService service = new StorageTrackingService(failing, 100);
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, stock(start, row(unchanged, 5), row(removed, 7)), true, false, 0));
            assertTrue(
                service.submit(grid, 1, stock(start + 1000, row(unchanged, 5), row(uncertain, 9)), true, false, 0));
            assertTrue(service.submit(grid, 1, stock(start + 3000, row(unchanged, 5)), true, false, 0));
            service.close();

            StorageHistory wide = service.history(grid, unchanged, start + 1, 100);
            assertFalse(wide.complete());
            assertEquals(
                Arrays.asList(start + 1000, start + 3000),
                wide.points()
                    .stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.GAP)
                    .map(StorageHistoryPoint::observedAt)
                    .collect(Collectors.toList()));
            assertEquals(5, baseline(service.history(grid, unchanged, start + 3001, 100)).quantity());
            assertEquals(0, baseline(service.history(grid, removed, start + 3001, 100)).quantity());
            StorageHistory missing = service.history(grid, uncertain, start + 3001, 100);
            assertTrue(missing.complete());
            assertNull(missing.baseline(), "An uncertain resource must not survive the recovery barrier");
            assertTrue(
                missing.points()
                    .isEmpty());
        }
    }

    @Test
    void flowOnlyRecoveryNeverReplaysUncertainTrafficAndKeepsTheNextStockBaselinePending() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long start = System.currentTimeMillis() - 10000;
        try (HsqlStorageHistoryStore stored = store()) {
            WorldHistoryFixtures.register(stored, grid);
            StorageTrackingService service = new StorageTrackingService(failAfterCommit(stored, start + 1000), 100);
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, stock(start, row(item, 5)), true, true, start - 1000));
            assertTrue(
                service.submit(grid, 1, withFlow(stock(start + 1000, row(item, 9)), item, 7, true), true, true, start));
            assertTrue(service.submit(grid, 1, flow(start + 3000, item, 11, true), false, true, start + 1000));
            assertTrue(service.submit(grid, 1, flow(start + 4000, item, 13, true), false, true, start + 3000));
            assertTrue(
                service.submit(
                    grid,
                    1,
                    new StorageObservation(
                        start + 5000,
                        false,
                        true,
                        Collections.singletonList(row(item, 5)),
                        Collections.emptyList(),
                        Collections.singletonMap(item, description(item)),
                        Collections.emptyList()),
                    true,
                    false,
                    0));
            service.close();

            StorageHistory history = service.history(grid, item, start, 100);
            List<StorageHistoryPoint> flows = history.points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                .collect(Collectors.toList());
            assertEquals(
                Arrays.asList(7L, 11L, 13L),
                flows.stream()
                    .map(StorageHistoryPoint::input)
                    .collect(Collectors.toList()));
            assertTrue(
                history.points()
                    .stream()
                    .anyMatch(
                        point -> point.observedAt() == start + 3000
                            && point.kind() == StorageHistoryPoint.Kind.FLOW_OBSERVATION),
                "Database recovery must retain the current capture's complete flow interval");
            StorageHistory future = service.history(grid, item, start + 3001, 100);
            assertTrue(future.complete());
            assertNull(future.baseline(), "Flow recovery cannot reuse a stock amount from before its gap");
            assertEquals(5, baseline(service.history(grid, item, start + 5001, 100)).quantity());
            assertTrue(
                future.points()
                    .stream()
                    .anyMatch(
                        point -> point.observedAt() == start + 5000 && point.kind() == StorageHistoryPoint.Kind.STOCK
                            && point.quantity() == 5));
        }
    }

    @Test
    void repeatedFailuresRetryOnlyDurableSamplesAndLogOnceWhileAnotherGridKeepsRecording() throws Exception {
        StableKey grid = StableKey.random();
        StableKey healthy = StableKey.random();
        StableKey item = StableKey.random();
        AtomicBoolean unavailable = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger warnings = new AtomicInteger();
        InMemoryStorageHistoryStore stored = new InMemoryStorageHistoryStore();
        IStorageHistoryStore failing = new IStorageHistoryStore() {

            @Override
            public void append(@NotNull StableKey key, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
                @NotNull List<StorageHistoryPoint> points) throws IOException {
                if (key.equals(grid)) {
                    attempts.incrementAndGet();
                    if (unavailable.get()) throw new IOException("Disk unavailable");
                }
                stored.append(key, descriptions, points);
            }

            @Override
            public @NotNull StorageHistory read(@NotNull StableKey key, @Nullable StableKey resource, long from,
                int limit) {
                return stored.read(key, resource, from, limit);
            }
        };
        AbstractAppender appender = new AbstractAppender("history-recovery", null, null, true, Property.EMPTY_ARRAY) {

            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN && event.getMessage()
                    .getFormattedMessage()
                    .contains(grid.toString())) warnings.incrementAndGet();
            }
        };
        Logger logger = (Logger) LogManager.getLogger("ae2webintegration");
        Level previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try (StorageTrackingService service = new StorageTrackingService(failing, 100)) {
            service.open(grid, 1);
            service.open(healthy, 1);
            assertTrue(service.submit(grid, 1, stock(10, row(item, 5)), true, false, 0));
            awaitHealthySample(service, healthy, item, 10);
            assertFalse(
                service.history(grid, item, 0, 100)
                    .complete());
            assertEquals(1, attempts.get());
            assertEquals(1, warnings.get());

            assertTrue(service.submit(grid, 1, stock(20, row(item, 7)), false, false, 0));
            awaitHealthySample(service, healthy, item, 20);
            assertEquals(1, attempts.get(), "Viewer-only observations must not drive database retries");
            assertFalse(
                service.history(grid, item, 0, 100)
                    .complete());

            assertTrue(service.submit(grid, 1, stock(30, row(item, 9)), true, false, 0));
            awaitHealthySample(service, healthy, item, 30);
            assertEquals(2, attempts.get());
            assertEquals(1, warnings.get());
            assertFalse(
                service.history(grid, item, 0, 100)
                    .complete());

            unavailable.set(false);
            assertTrue(service.submit(grid, 1, stock(40, row(item, 11)), true, false, 0));
            awaitHealthySample(service, healthy, item, 40);
            assertEquals(3, attempts.get());
            assertTrue(
                service.history(grid, item, 41, 100)
                    .complete());
            assertEquals(
                Arrays.asList(10L, 40L),
                service.history(grid, item, 0, 100)
                    .points()
                    .stream()
                    .filter(point -> point.kind() == StorageHistoryPoint.Kind.GAP)
                    .map(StorageHistoryPoint::observedAt)
                    .collect(Collectors.toList()));

            unavailable.set(true);
            assertTrue(service.submit(grid, 1, stock(50, row(item, 12)), true, false, 0));
            awaitHealthySample(service, healthy, item, 50);
            assertEquals(4, attempts.get());
            assertEquals(2, warnings.get(), "A separate outage should produce a new warning");
            assertFalse(
                service.history(grid, item, 41, 100)
                    .complete());
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void recoveryRestatesAKnownZeroEvenWhenItsRecipeDisappearsAfterAnUncertainPositiveWrite() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long start = System.currentTimeMillis() - 10000;
        try (HsqlStorageHistoryStore stored = store()) {
            WorldHistoryFixtures.register(stored, grid);
            StorageTrackingService service = new StorageTrackingService(failAfterCommit(stored, start + 1000), 100);
            service.open(grid, 1);
            assertTrue(service.submit(grid, 1, stock(start, row(item, 5)), true, false, 0));
            assertTrue(
                service.submit(grid, 1, stock(start + 500, new StorageObservation.Row(item, 0, true)), true, false, 0));
            assertTrue(service.submit(grid, 1, stock(start + 1000, row(item, 9)), true, false, 0));
            assertTrue(service.submit(grid, 1, stock(start + 3000), true, false, 0));
            service.close();

            StorageHistory recovered = service.history(grid, item, start + 3001, 100);
            assertEquals(0, baseline(recovered).quantity());
            assertEquals(start + 3000, baseline(recovered).observedAt());
        }
    }

    @Test
    void recoveringTheDatabaseDoesNotCertifyIncompleteNativeStockOrFlow() throws Exception {
        StableKey grid = StableKey.random();
        StableKey healthy = StableKey.random();
        StableKey item = StableKey.random();
        long start = System.currentTimeMillis() - 10000;
        try (HsqlStorageHistoryStore stored = store()) {
            WorldHistoryFixtures.register(stored, grid);
            WorldHistoryFixtures.register(stored, healthy);
            try (StorageTrackingService service = new StorageTrackingService(
                failAfterCommit(stored, start + 1000),
                100)) {
                service.open(grid, 1);
                service.open(healthy, 1);
                assertTrue(service.submit(grid, 1, stock(start, row(item, 5)), true, true, start - 1000));
                assertTrue(service.submit(grid, 1, stock(start + 1000, row(item, 9)), true, false, 0));
                awaitHealthySample(service, healthy, item, start + 1500);
                service.invalidate(grid, 1);
                StorageObservation partial = new StorageObservation(
                    start + 2000,
                    false,
                    false,
                    true,
                    Collections.emptyList(),
                    Collections.singletonList(new StorageObservation.Flow(item, 7, 0, 1, 0)),
                    Collections.singletonMap(item, description(item)),
                    Collections.emptyList());
                assertTrue(service.submit(grid, 1, partial, true, true, start));
                assertTrue(service.submit(grid, 1, flow(start + 3000, item, 11, true), false, true, start + 2000));
                assertTrue(service.submit(grid, 1, stock(start + 4000, row(item, 9)), true, false, 0));
                service.close();

                StorageHistory history = service.history(grid, item, start + 2000, 100);
                assertFalse(history.complete());
                assertTrue(
                    history.points()
                        .stream()
                        .anyMatch(
                            point -> point.observedAt() == start + 2000 && point.kind() == StorageHistoryPoint.Kind.FLOW
                                && point.input() == 7));
                assertFalse(
                    history.points()
                        .stream()
                        .anyMatch(
                            point -> point.observedAt() == start + 2000
                                && (point.kind() == StorageHistoryPoint.Kind.OBSERVATION
                                    || point.kind() == StorageHistoryPoint.Kind.FLOW_OBSERVATION)));
                assertTrue(
                    service.history(grid, item, start + 2001, 100)
                        .complete());
                StorageHistoryPoint restored = baseline(service.history(grid, item, start + 4001, 100));
                assertEquals(9, restored.quantity());
                assertEquals(start + 4000, restored.observedAt());
            }
        }
    }

    @Test
    void aFailedFirstFlowWriteRecoversWithoutStockOrReplayingTheLostInterval() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        InMemoryStorageHistoryStore stored = new InMemoryStorageHistoryStore();
        StorageTrackingService service = new StorageTrackingService(failAt(stored, 10, false), 100);
        service.open(grid, 1);
        assertTrue(service.submit(grid, 1, flow(10, item, 7, true), false, true, 0));
        assertTrue(service.submit(grid, 1, flow(20, item, 11, true), false, true, 10));
        assertTrue(service.submit(grid, 1, flow(30, item, 13, true), false, true, 20));
        service.close();

        StorageHistory history = service.history(grid, item, 0, 100);
        assertFalse(history.complete());
        assertEquals(
            Arrays.asList(11L, 13L),
            history.points()
                .stream()
                .filter(point -> point.kind() == StorageHistoryPoint.Kind.FLOW)
                .map(StorageHistoryPoint::input)
                .collect(Collectors.toList()));
        assertFalse(
            history.points()
                .stream()
                .anyMatch(point -> point.kind() == StorageHistoryPoint.Kind.STOCK));
        assertTrue(
            service.history(grid, item, 21, 100)
                .complete());
    }

    @Test
    void aReadStartedDuringAnOutageCannotBeCertifiedByAnOverlappingRecovery() throws Exception {
        StableKey grid = StableKey.random();
        StableKey healthy = StableKey.random();
        StableKey item = StableKey.random();
        IStorageHistoryStore writes = failAt(new InMemoryStorageHistoryStore(), 10, false);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pauseNextRead = new AtomicBoolean();
        IStorageHistoryStore blockedRead = new IStorageHistoryStore() {

            @Override
            public void append(@NotNull StableKey key, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
                @NotNull List<StorageHistoryPoint> points) throws IOException {
                writes.append(key, descriptions, points);
            }

            @Override
            public @NotNull StorageHistory read(@NotNull StableKey key, @Nullable StableKey resource, long from,
                int limit) throws IOException {
                StorageHistory result = writes.read(key, resource, from, limit);
                if (key.equals(grid) && pauseNextRead.getAndSet(false)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test reader did not resume");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread()
                            .interrupt();
                        throw new IOException(interrupted);
                    }
                }
                return result;
            }
        };
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (StorageTrackingService service = new StorageTrackingService(blockedRead, 100)) {
            service.open(grid, 1);
            service.open(healthy, 1);
            assertTrue(service.submit(grid, 1, stock(10, row(item, 5)), true, false, 0));
            awaitHealthySample(service, healthy, item, 20);
            assertFalse(
                service.history(grid, item, 21, 100)
                    .complete());
            pauseNextRead.set(true);
            Future<StorageHistory> oldRead = reader.submit(() -> service.history(grid, item, 21, 100));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(service.submit(grid, 1, stock(30, row(item, 7)), true, false, 0));
            awaitHealthySample(service, healthy, item, 40);
            assertTrue(
                service.history(grid, item, 31, 100)
                    .complete());
            release.countDown();
            assertFalse(
                oldRead.get(5, TimeUnit.SECONDS)
                    .complete(),
                "Recovery cannot certify data captured by a read during the outage");
        } finally {
            release.countDown();
            reader.shutdownNow();
        }
    }

    private HsqlStorageHistoryStore store() {
        return WorldHistoryFixtures.create(directory.resolve("database/history"), new Range(1, 365, 7), 1000);
    }

    private static IStorageHistoryStore failAfterCommit(IStorageHistoryStore stored, long at) {
        return failAt(stored, at, true);
    }

    private static IStorageHistoryStore failAt(IStorageHistoryStore stored, long at, boolean commitBeforeFailure) {
        // The test owns the delegate, allowing reads after the service has drained and closed its wrapper.
        return new IStorageHistoryStore() {

            @Override
            public void append(@NotNull StableKey grid, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
                @NotNull List<StorageHistoryPoint> points) throws IOException {
                boolean failed = points.get(points.size() - 1)
                    .observedAt() == at;
                if (failed && !commitBeforeFailure) throw new IOException("Append rejected");
                stored.append(grid, descriptions, points);
                if (failed) throw new IOException("Uncertain commit");
            }

            @Override
            public @NotNull StorageHistory read(@NotNull StableKey grid, @Nullable StableKey item, long from, int limit)
                throws IOException {
                return stored.read(grid, item, from, limit);
            }
        };
    }

    private static StorageHistoryPoint baseline(StorageHistory history) {
        assertTrue(history.complete());
        assertNotNull(history.baseline());
        return history.baseline();
    }

    private static void awaitHealthySample(StorageTrackingService service, StableKey grid, StableKey item, long at)
        throws Exception {
        assertTrue(service.submit(grid, 1, stock(at, row(item, at)), true, false, 0));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.history(grid, item, at, 100)
            .points()
            .isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
        assertFalse(
            service.history(grid, item, at, 100)
                .points()
                .isEmpty(),
            "Healthy grid did not progress");
    }

    private static StorageObservation stock(long at, StorageObservation.Row... rows) {
        Map<StableKey, ResourceDescriptor> descriptions = Arrays.stream(rows)
            .collect(Collectors.toMap(StorageObservation.Row::itemKey, row -> description(row.itemKey())));
        return new StorageObservation(
            at,
            true,
            true,
            Arrays.asList(rows),
            Collections.emptyList(),
            descriptions,
            Collections.emptyList());
    }

    private static StorageObservation.Row row(StableKey key, long quantity) {
        return new StorageObservation.Row(key, quantity, false);
    }

    private static StorageObservation flow(long at, StableKey key, long input, boolean complete) {
        return withFlow(
            new StorageObservation(
                at,
                false,
                complete,
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.singletonMap(key, description(key)),
                Collections.emptyList()),
            key,
            input,
            complete);
    }

    private static StorageObservation withFlow(StorageObservation observation, StableKey key, long input,
        boolean complete) {
        return new StorageObservation(
            observation.observedAt(),
            observation.full(),
            complete,
            observation.stockObserved(),
            observation.rows(),
            Collections.singletonList(new StorageObservation.Flow(key, input, 0, 1, 0)),
            observation.descriptions(),
            observation.unkeyed());
    }

    private static ResourceDescriptor description(StableKey key) {
        return new ResourceDescriptor("test", "item", 0, 0, "Item", ResourceType.ITEM, key, null);
    }
}
