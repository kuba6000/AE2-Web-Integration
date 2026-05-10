package pl.kuba6000.ae2webintegration.core.utils;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {

    private final int MAX_REQUESTS_PER_INTERVAL;
    private final int RESET_INTERVAL_MS;
    private final int RESET_WHITELIST_INTERVAL_MS; // 1 hour

    public RateLimiter(int maxRequestsPerInterval, int resetIntervalMs, int resetWhitelistIntervalMs) {
        MAX_REQUESTS_PER_INTERVAL = maxRequestsPerInterval;
        RESET_INTERVAL_MS = resetIntervalMs;
        RESET_WHITELIST_INTERVAL_MS = resetWhitelistIntervalMs;
    }

    private volatile long lastUpdate = 0;
    private final ConcurrentHashMap<InetAddress, Integer> requestCounter = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InetAddress, Long> whitelist = new ConcurrentHashMap<>();

    public boolean isAllowed(InetAddress userId) {
        updateRequests();

        if (whitelist.containsKey(userId)) {
            return true; // User is whitelisted
        }

        return requestCounter.merge(userId, 1, Integer::sum) < MAX_REQUESTS_PER_INTERVAL;
    }

    public void ensureWhitelisted(InetAddress userId) {
        whitelist.put(userId, System.currentTimeMillis());
    }

    private void updateRequests() {
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastUpdate > RESET_INTERVAL_MS) { // Reset every 60 seconds
            requestCounter.clear();
            lastUpdate = currentTime;
        }

        whitelist.entrySet()
            .removeIf(entry -> currentTime - entry.getValue() > RESET_WHITELIST_INTERVAL_MS); // Remove entries older
                                                                                              // than 1 hour
    }

}
