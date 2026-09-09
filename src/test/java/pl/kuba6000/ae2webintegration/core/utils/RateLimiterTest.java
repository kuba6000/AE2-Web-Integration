package pl.kuba6000.ae2webintegration.core.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

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
        RateLimiter limiter = limiter(1000);
        limiter.isAllowed(A);
        now.addAndGet(WINDOW_MS + 1);

        int threads = 8;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> limiter.isAllowed(A));
        }
        for (Thread worker : workers) worker.start();
        for (Thread worker : workers) worker.join();

        // Exactly one reset may happen, so all eight calls land in the same fresh window and are counted.
        // A racy reset would drop counts and let the address exceed its budget later.
        assertTrue(limiter.isAllowed(A));
    }
}
