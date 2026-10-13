package pl.kuba6000.ae2webintegration.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class RateLimiterTest {

    private static final int WINDOW_MS = 60_000;

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private RateLimiter limiter(int max) {
        return new RateLimiter(max, WINDOW_MS, now::get);
    }

    private static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final InetAddress A = addr("203.0.113.1");
    private static final InetAddress B = addr("203.0.113.2");

    @Test
    void allowsExactlyTheConfiguredNumberOfRequests() {
        RateLimiter limiter = limiter(20);
        for (int i = 1; i <= 20; i++) {
            assertTrue(limiter.isAllowed(A), "request " + i + " of 20 should be allowed");
        }
        assertFalse(limiter.isAllowed(A), "the 21st request should be denied");
    }

    @Test
    void aLimitOfOneAllowsOneRequest() {
        // The config minimum is 1. Denying even the first request made the web interface unusable.
        RateLimiter limiter = limiter(1);
        assertTrue(limiter.isAllowed(A));
        assertFalse(limiter.isAllowed(A));
    }

    @Test
    void addressesAreCountedSeparately() {
        RateLimiter limiter = limiter(2);
        assertTrue(limiter.isAllowed(A));
        assertTrue(limiter.isAllowed(A));
        assertFalse(limiter.isAllowed(A));

        assertTrue(limiter.isAllowed(B), "a different address must have its own budget");
        assertTrue(limiter.isAllowed(B));
        assertFalse(limiter.isAllowed(B));
    }

    @Test
    void budgetIsRestoredAfterTheWindowElapses() {
        RateLimiter limiter = limiter(2);
        assertTrue(limiter.isAllowed(A));
        assertTrue(limiter.isAllowed(A));
        assertFalse(limiter.isAllowed(A));

        now.addAndGet(WINDOW_MS + 1);

        assertTrue(limiter.isAllowed(A), "a new window should restore the budget");
    }

    @Test
    void windowDoesNotResetEarly() {
        RateLimiter limiter = limiter(2);
        assertTrue(limiter.isAllowed(A));
        assertTrue(limiter.isAllowed(A));

        now.addAndGet(WINDOW_MS - 1);

        assertFalse(limiter.isAllowed(A), "the window must not reset before it elapses");
    }

    @Test
    void concurrentCallsAtTheWindowBoundaryResetOnlyOnce() throws Exception {
        int threads = 8;
        int rounds = 1000;
        RateLimiter limiter = limiter(threads + 1);
        limiter.isAllowed(A);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads + 1);
        CyclicBarrier done = new CyclicBarrier(threads + 1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    for (int round = 0; round < rounds; round++) {
                        start.await(5, TimeUnit.SECONDS);
                        if (limiter.isAllowed(A)) accepted.incrementAndGet();
                        done.await(5, TimeUnit.SECONDS);
                    }
                    return null;
                }));
            }
            for (int round = 0; round < rounds; round++) {
                accepted.set(0);
                now.addAndGet(WINDOW_MS + 1);
                start.await(5, TimeUnit.SECONDS);
                done.await(5, TimeUnit.SECONDS);
                assertEquals(threads, accepted.get(), "requests in the fresh window must be admitted");
                assertTrue(limiter.isAllowed(A), "one request remains in the new window");
                assertFalse(limiter.isAllowed(A), "concurrent requests must all consume the same window's budget");
            }
            for (Future<?> result : results) result.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
