package pl.kuba6000.ae2webintegration.core.utils;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
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

    private long windowStart;
    private final Map<InetAddress, Integer> requestCounter = new HashMap<>();

    public RateLimiter(int maxRequestsPerInterval, int resetIntervalMs) {
        this(maxRequestsPerInterval, resetIntervalMs, System::currentTimeMillis);
    }

    public RateLimiter(int maxRequestsPerInterval, int resetIntervalMs, LongSupplier clock) {
        this.maxRequestsPerInterval = maxRequestsPerInterval;
        this.resetIntervalMs = resetIntervalMs;
        this.clock = clock;
    }

    public synchronized boolean isAllowed(InetAddress client) {
        long now = clock.getAsLong();
        if (now - windowStart > resetIntervalMs) {
            requestCounter.clear();
            windowStart = now;
        }
        return requestCounter.merge(client, 1, Integer::sum) <= maxRequestsPerInterval;
    }
}
