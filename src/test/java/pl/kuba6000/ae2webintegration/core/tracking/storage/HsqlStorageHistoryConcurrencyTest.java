package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

class HsqlStorageHistoryConcurrencyTest {

    @TempDir
    Path directory;

    @Test
    @Timeout(20)
    void readersFinishWhileAnAppendIsStillInProgress() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService tasks = Executors.newFixedThreadPool(3);
        try (HsqlStorageHistoryStore store = new HsqlStorageHistoryStore(
            directory.resolve("history"),
            new pl.kuba6000.ae2webintegration.core.config.DataPolicy.Range(1, 365, 7),
            100,
            Collections.emptyMap())) {
            store.append(
                grid,
                Collections.singletonMap(item, description(item, "Before")),
                Collections.singletonList(stock(item, now, 1)));
            Map<StableKey, ResourceDescriptor> delayed = delayedDescription(item, writing, release);
            Future<?> writer = tasks.submit(() -> {
                store.append(grid, delayed, Collections.singletonList(stock(item, now + 1, 2)));
                return null;
            });
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                Future<StorageHistory> first = tasks.submit(() -> store.read(grid, item, now, 10));
                Future<StorageHistory> second = tasks.submit(() -> store.read(grid, item, now, 10));
                assertEquals(
                    1,
                    first.get(5, TimeUnit.SECONDS)
                        .points()
                        .get(0)
                        .quantity());
                assertEquals(
                    1,
                    second.get(5, TimeUnit.SECONDS)
                        .points()
                        .get(0)
                        .quantity());
                assertFalse(writer.isDone());
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
            assertEquals(
                2,
                store.read(grid, item, now + 1, 10)
                    .points()
                    .get(0)
                    .quantity());
        } finally {
            release.countDown();
            tasks.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void concurrentResponsesKeepMeasurementsAndDescriptionsInOneCommittedSnapshot() throws Exception {
        StableKey grid = StableKey.random();
        List<StableKey> items = new ArrayList<>();
        for (int index = 0; index < 12; index++) items.add(StableKey.random());
        long now = System.currentTimeMillis();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService tasks = Executors.newFixedThreadPool(4);
        try (HsqlStorageHistoryStore store = new HsqlStorageHistoryStore(
            directory.resolve("history"),
            new pl.kuba6000.ae2webintegration.core.config.DataPolicy.Range(1, 365, 7),
            items.size(),
            Collections.emptyMap())) {
            appendRevision(store, grid, items, now, 0);
            Future<?> writer = tasks.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int revision = 1; revision <= 100; revision++) appendRevision(store, grid, items, now, revision);
                return null;
            });
            List<Future<?>> queries = new ArrayList<>();
            for (int reader = 0; reader < 3; reader++) queries.add(tasks.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int attempt = 0; attempt < 100; attempt++) {
                    StorageHistory history = store.read(grid, null, now, 100);
                    assertEquals(
                        items.size(),
                        history.points()
                            .size());
                    long revision = history.points()
                        .get(0)
                        .quantity();
                    for (StorageHistoryPoint point : history.points()) {
                        assertEquals(revision, point.quantity());
                        assertEquals(
                            Long.toString(revision),
                            history.descriptions()
                                .get(point.itemKey())
                                .displayName());
                    }
                }
                return null;
            }));
            start.countDown();
            writer.get(20, TimeUnit.SECONDS);
            for (Future<?> query : queries) query.get(20, TimeUnit.SECONDS);
            assertEquals(
                100,
                store.read(grid, items.get(0), now, 100)
                    .points()
                    .get(0)
                    .quantity());
        } finally {
            start.countDown();
            tasks.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void closeKeepsOwnershipUntilAcceptedAppendCompletesAndRejectsLaterOperations() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        Path path = directory.resolve("history");
        try (
            HsqlStorageHistoryStore store = new HsqlStorageHistoryStore(
                path,
                new pl.kuba6000.ae2webintegration.core.config.DataPolicy.Range(1, 365, 7),
                100,
                Collections.emptyMap());
            HsqlStorageHistoryStore next = new HsqlStorageHistoryStore(
                path,
                new pl.kuba6000.ae2webintegration.core.config.DataPolicy.Range(1, 365, 7),
                100,
                Collections.emptyMap())) {
            Future<?> writer = tasks.submit(() -> {
                store.append(
                    grid,
                    delayedDescription(item, writing, release),
                    Collections.singletonList(stock(item, now, 2)));
                return null;
            });
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<?> close = tasks.submit(() -> {
                store.close();
                return null;
            });
            try {
                assertThrows(IOException.class, () -> next.read(grid, item, now, 10));
                assertFalse(close.isDone());
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertThrows(IOException.class, () -> store.read(grid, item, now, 10));
            assertThrows(IOException.class, () -> store.append(grid, Collections.emptyMap(), Collections.emptyList()));
            assertEquals(
                2,
                next.read(grid, item, now, 10)
                    .points()
                    .get(0)
                    .quantity());
        } finally {
            release.countDown();
            tasks.shutdownNow();
        }
    }

    @Test
    @Timeout(20)
    void concurrentFirstAccessInitializesOnceAndReleasesOwnershipOnClose() throws Exception {
        StableKey grid = StableKey.random();
        StableKey item = StableKey.random();
        long now = System.currentTimeMillis();
        Path path = directory.resolve("history");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService tasks = Executors.newFixedThreadPool(6);
        try (HsqlStorageHistoryStore store = new HsqlStorageHistoryStore(
            path,
            new pl.kuba6000.ae2webintegration.core.config.DataPolicy.Range(1, 365, 7),
            100,
            Collections.emptyMap())) {
            List<Future<?>> operations = new ArrayList<>();
            operations.add(tasks.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                store.append(
                    grid,
                    Collections.singletonMap(item, description(item, "First")),
                    Collections.singletonList(stock(item, now, 1)));
                return null;
            }));
            for (int index = 0; index < 5; index++) operations.add(tasks.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                StorageHistory history = store.read(grid, item, now, 10);
                assertTrue(
                    history.points()
                        .isEmpty()
                        || history.points()
                            .equals(Collections.singletonList(stock(item, now, 1))));
                return null;
            }));
            start.countDown();
            for (Future<?> operation : operations) operation.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            tasks.shutdownNow();
        }
        try (HsqlStorageHistoryStore reopened = new HsqlStorageHistoryStore(
            path,
            new pl.kuba6000.ae2webintegration.core.config.DataPolicy.Range(1, 365, 7),
            100,
            Collections.emptyMap())) {
            assertEquals(
                1,
                reopened.read(grid, item, now, 10)
                    .points()
                    .get(0)
                    .quantity());
        }
    }

    @Test
    void interruptedReadDoesNotInitializeOrConsumeReaderCapacity() throws Exception {
        Path path = directory.resolve("database/history");
        StableKey grid = StableKey.random();
        try (HsqlStorageHistoryStore store = new HsqlStorageHistoryStore(
            path,
            new pl.kuba6000.ae2webintegration.core.config.DataPolicy.Range(1, 365, 7),
            100,
            Collections.emptyMap())) {
            for (int attempt = 0; attempt < 10; attempt++) {
                Thread.currentThread()
                    .interrupt();
                try {
                    assertThrows(IOException.class, () -> store.read(grid, null, 0, 1));
                    assertTrue(
                        Thread.currentThread()
                            .isInterrupted());
                } finally {
                    Thread.interrupted();
                }
            }
            assertFalse(Files.exists(path.getParent()));
            assertTrue(
                store.read(grid, null, 0, 1)
                    .points()
                    .isEmpty());
        }
    }

    private static Map<StableKey, ResourceDescriptor> delayedDescription(StableKey item, CountDownLatch writing,
        CountDownLatch release) {
        return new AbstractMap<StableKey, ResourceDescriptor>() {

            @Override
            public @NotNull Set<Entry<StableKey, ResourceDescriptor>> entrySet() {
                writing.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Append was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread()
                        .interrupt();
                    throw new AssertionError(interrupted);
                }
                return Collections.singletonMap(item, description(item, "After"))
                    .entrySet();
            }
        };
    }

    private static void appendRevision(HsqlStorageHistoryStore store, StableKey grid, List<StableKey> items, long time,
        int revision) throws Exception {
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        List<StorageHistoryPoint> points = new ArrayList<>();
        for (StableKey item : items) {
            descriptions.put(item, description(item, Integer.toString(revision)));
            points.add(stock(item, time + revision, revision));
        }
        store.append(grid, descriptions, points);
    }

    private static StorageHistoryPoint stock(StableKey item, long time, long quantity) {
        return new StorageHistoryPoint(time, 1, StorageHistoryPoint.Kind.STOCK, item, quantity, 0, 0, null);
    }

    private static ResourceDescriptor description(StableKey item, String name) {
        return new ResourceDescriptor("test", "item", 0, 0, name, ResourceType.ITEM, item, item);
    }
}
