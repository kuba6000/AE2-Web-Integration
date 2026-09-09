package pl.kuba6000.ae2webintegration.core.utils;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-address request budget for <b>unauthenticated</b> traffic, guarding the login endpoint against
 * password guessing.
 * <p>
 * There is deliberately no whitelist here. Callers decide who is exempt: a request carrying a valid
 * session token never reaches this class at all. An earlier version whitelisted an IP once anyone from it
 * logged in, which meant a single login behind NAT or a reverse proxy disabled the limit for everyone
 * sharing that address - defeating the purpose exactly where it mattered.
 */
public class RateLimiter {

    private final int maxRequestsPerInterval;
    private final int resetIntervalMs;
    private final LongSupplier clock;

    private final AtomicLong windowStart = new AtomicLong(0);
    private final ConcurrentHashMap<InetAddress, Integer> requestCounter = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequestsPerInterval, int resetIntervalMs) {
        this(maxRequestsPerInterval, resetIntervalMs, System::currentTimeMillis);
    }

    public RateLimiter(int maxRequestsPerInterval, int resetIntervalMs, LongSupplier clock) {
        this.maxRequestsPerInterval = maxRequestsPerInterval;
        this.resetIntervalMs = resetIntervalMs;
        this.clock = clock;
    }

    public boolean isAllowed(InetAddress client) {
        rollWindow();
        return requestCounter.merge(client, 1, Integer::sum) <= maxRequestsPerInterval;
    }

    /**
     * Only the thread that wins the compare-and-set clears the counters. Without it two threads crossing
     * the boundary together could both clear, discarding requests counted in between.
     */
    private void rollWindow() {
        long now = clock.getAsLong();
        long start = windowStart.get();
        if (now - start > resetIntervalMs && windowStart.compareAndSet(start, now)) {
            requestCounter.clear();
        }
    }
}
