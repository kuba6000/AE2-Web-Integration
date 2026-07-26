package pl.kuba6000.ae2webintegration.core;

import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class CoreEngine {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    /**
     * Wall clock a single tick may spend draining queued requests, one tenth of a 50 ms tick.
     * <p>
     * Time rather than a request count, because the harm being bounded is overrunning the tick and the
     * cost of a request varies by orders of magnitude - {@code /items} on a large network against
     * {@code /gettracking} - so no count can bound the time.
     */
    static final long DRAIN_BUDGET_NANOS = 5_000_000L;

    // Populated by the interface layer from the buildscript-generated mod version.
    private static volatile String modVersion;

    public static void init(IServerPlatform serverPlatform, String modVersion, String versionIdentifier) {
        VersionChecker.setVersionIdentifier(versionIdentifier);
        AE2Controller.serverPlatform = serverPlatform;
        Config.init(serverPlatform.getConfigDirectory());
        CoreEngine.modVersion = modVersion;
        loadData();
    }

    private static void loadData() {
        CoreData.loadData();
        GridData.loadData();
    }

    public static void onServerStarted() {
        AE2Controller.init();
        StartupHandler.logOpenAdminAccessWarning();
        StartupHandler.logOutdatedWarning();
        StartupHandler.handleDiscordIntegration();
    }

    /**
     * Runs the queued synced requests on the server thread. The interface layer supplies nothing but the
     * platform's tick event - cadence, bounding and fault handling are decisions that belong here, not in
     * four copies of an event handler that no test can reach.
     */
    public static void onServerTick() {
        drainRequests(System::nanoTime);
    }

    /** The clock is the one thing a test cannot control from outside, as in {@code RateLimiter}. */
    static void drainRequests(LongSupplier nanoClock) {
        long deadline = nanoClock.getAsLong() + DRAIN_BUDGET_NANOS;
        ISyncedRequest request;
        while ((request = AE2Controller.requests.poll()) != null) {
            try {
                request.handle(AE2Controller.AE2Interface);
            } catch (Throwable t) {
                // Throwable, not Exception, and on purpose. This runs inside the server tick event, which
                // rethrows: anything escaping here stops being a failed request and becomes a stopped
                // server. A runaway handler ending in StackOverflowError should not cost the world, and an
                // OutOfMemoryError resurfaces at the next allocation regardless.
                LOG.error(
                    "Synced request " + request.getClass()
                        .getSimpleName() + " failed",
                    t);
                request.failIfPending("INTERNAL_ERROR");
            }
            // Checked after handling, never before, so a request costlier than the whole budget still runs
            // and can never starve the queue.
            if (nanoClock.getAsLong() >= deadline) {
                break;
            }
        }
    }

    public static void onServerStopping() {
        AE2Controller.stopHTTPServer();
        // Authorization must not survive into the next world loaded in this JVM.
        GridAccessSessions.clear();
    }

    public static String getModVersion() {
        return modVersion;
    }
}
