package pl.kuba6000.ae2webintegration.core.tracking.storage;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.function.Predicate;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Waits for observable history output without depending on worker presentation state. */
public final class StorageTrackingTestFixtures {

    private StorageTrackingTestFixtures() {}

    public static StorageHistory awaitHistory(StorageTrackingService service, StableKey grid, long from,
        Predicate<StorageHistory> ready) throws Exception {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        do {
            StorageHistory history = service.history(grid, null, from, 100);
            if (ready.test(history)) return history;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected history was not recorded for " + grid);
    }
}
