package pl.kuba6000.ae2webintegration.core;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.utils.ReleaseManifest;
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
    static final long PLAN_SWEEP_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    static final int PLAN_SWEEP_GRIDS_PER_TICK = 8;

    private static long nextPlanSweepNanos;
    private static boolean planSweepScheduled;
    private static boolean planSweepInProgress;

    // Populated by the interface layer from the buildscript-generated mod version.
    private static volatile String modVersion;
    private static String versionIdentifier;
    private static volatile @Nullable VersionChecker versionChecker;
    private static boolean serverRunning;

    public static void init(IServerPlatform serverPlatform, String modVersion, String versionIdentifier) {
        serverRunning = false;
        stopVersionChecker();
        CoreEngine.versionIdentifier = versionIdentifier;
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
        serverRunning = true;
        AE2Controller.init();
        StartupHandler.logOpenAdminAccessWarning();
        maintainVersionChecker();
        StartupHandler.handleDiscordIntegration();
    }

    /**
     * Runs the queued synced requests on the server thread. The interface layer supplies nothing but the
     * platform's tick event - cadence, bounding and fault handling are decisions that belong here, not in
     * four copies of an event handler that no test can reach.
     */
    public static void onServerTick() {
        drainRequests(System::nanoTime);
        runPlanMaintenance(System.nanoTime());
        maintainVersionChecker();
    }

    private static void maintainVersionChecker() {
        if (!serverRunning) return;
        if (!Config.CHECK_FOR_UPDATES()) {
            stopVersionChecker();
        } else if (versionChecker == null && modVersion != null) {
            try {
                versionChecker = new VersionChecker(
                    new URL("https://raw.githubusercontent.com/kuba6000/AE2-Web-Integration/version/"),
                    modVersion,
                    versionIdentifier);
                versionChecker.checkForUpdates();
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static void stopVersionChecker() {
        if (versionChecker != null) {
            versionChecker.close();
            versionChecker = null;
        }
    }

    public static @Nullable ReleaseManifest.Release getAvailableUpdate() {
        VersionChecker checker = versionChecker;
        return checker == null ? null : checker.getAvailableUpdate();
    }

    /** Called from the platform's player-login event, which already runs on the server thread. */
    public static void onPlayerSeen(PlayerIdentity player) {
        CoreData.observePlayer(player);
    }

    /** The clock is the one thing a test cannot control from outside, as in {@code RateLimiter}. */
    static void drainRequests(LongSupplier nanoClock) {
        long deadline = nanoClock.getAsLong() + DRAIN_BUDGET_NANOS;
        IServerThreadTask task;
        while ((task = AE2Controller.requests.poll()) != null) {
            try {
                task.runOnServerThread(AE2Controller.AE2Interface);
            } catch (Throwable t) {
                // Throwable, not Exception, and on purpose. This runs inside the server tick event, which
                // rethrows: anything escaping here stops being a failed request and becomes a stopped
                // server. A runaway handler ending in StackOverflowError should not cost the world, and an
                // OutOfMemoryError resurfaces at the next allocation regardless.
                LOG.error(
                    "Server-thread task " + task.getClass()
                        .getSimpleName() + " failed",
                    t);
                task.failIfPending("INTERNAL_ERROR");
            }
            // Checked after handling, never before, so a request costlier than the whole budget still runs
            // and can never starve the queue.
            if (nanoClock.getAsLong() >= deadline) {
                break;
            }
        }
    }

    static synchronized void runPlanMaintenance(long nowNanos) {
        if (!planSweepInProgress) {
            if (planSweepScheduled && nowNanos - nextPlanSweepNanos < 0) {
                return;
            }
            planSweepInProgress = true;
        }

        if (GridData.evictExpiredCompletedPlans(nowNanos, PLAN_SWEEP_GRIDS_PER_TICK)) {
            planSweepInProgress = false;
            planSweepScheduled = true;
            nextPlanSweepNanos = nowNanos + PLAN_SWEEP_INTERVAL_NANOS;
        }
    }

    private static synchronized void resetPlanMaintenance() {
        nextPlanSweepNanos = 0L;
        planSweepScheduled = false;
        planSweepInProgress = false;
    }

    public static void onServerStopping() {
        serverRunning = false;
        stopVersionChecker();
        AE2Controller.stopHTTPServer();
        // Authorization must not survive into the next world loaded in this JVM.
        GridAccessSessions.clear();
    }

    public static synchronized void onServerStopped() {
        serverRunning = false;
        stopVersionChecker();
        // Defensive when startup failed partway or a platform omits the earlier stopping callback.
        AE2Controller.stopHTTPServer();
        AE2Controller.clearWorldState();
        GridAccessSessions.clear();
        AE2JobTracker.clearActiveJobs();
        GridData.clearRuntimeState();
        resetPlanMaintenance();
    }

    public static String getModVersion() {
        return modVersion;
    }
}
