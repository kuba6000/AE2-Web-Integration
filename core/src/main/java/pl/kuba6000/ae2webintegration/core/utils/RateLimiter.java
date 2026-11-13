package pl.kuba6000.ae2webintegration.core.utils;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-address request budget. Callers decide exemptions for ordinary authenticated traffic; credential
 * attempts remain bounded even when the request carries an existing valid session.
 * <p>
 * There is deliberately no whitelist here. Whitelisting an IP once anyone from it logs in would disable
 * protection for everyone sharing that address behind NAT or a reverse proxy.
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
