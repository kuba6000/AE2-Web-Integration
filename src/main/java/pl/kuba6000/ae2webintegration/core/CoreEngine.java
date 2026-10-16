package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalState;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalStreams;
import pl.kuba6000.ae2webintegration.core.themes.ThemeCatalog;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.HsqlStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;
import pl.kuba6000.ae2webintegration.core.utils.ReleaseManifest;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class CoreEngine {

    public static final GridIdentityRegistry GRID_IDENTITIES = new GridIdentityRegistry();

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    /**
     * Wall clock a single tick may spend draining queued requests, one tenth of a 50 ms tick.
     * <p>
     * Time rather than a request count, because the harm being bounded is overrunning the tick and the
     * cost of a request varies by orders of magnitude - {@code /items} on a large network against
     * {@code /gettracking} - so no count can bound the time.
     */
    static final long DRAIN_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    static final long PLAN_SWEEP_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    static final int PLAN_SWEEP_GRIDS_PER_TICK = 8;

    private static long nextPlanSweepNanos;
    private static boolean planSweepScheduled;
    private static boolean planSweepInProgress;

    // Populated by the interface layer from the buildscript-generated mod version.
    private static volatile String modVersion;
    private static volatile @Nullable VersionChecker versionChecker;
    private static boolean serverRunning;
    private static volatile @Nullable IconPack iconPack;
    private static volatile ThemeCatalog themes = new ThemeCatalog();
    private static volatile @Nullable StorageTrackingService storageTracking;
    private static @Nullable StorageTrackingCoordinator storageCoordinator;
    private static volatile @Nullable TerminalStreams terminalStreams;
    private static volatile @Nullable TerminalState terminalState;

    public static void init(@NotNull IServerPlatform serverPlatform) {
        serverRunning = false;
        stopStorageTracking();
        stopVersionChecker();
        AE2Controller.serverPlatform = serverPlatform;
        Config.init(serverPlatform.getConfigDirectory(), serverPlatform::getLegacyConfig);
        CoreEngine.modVersion = serverPlatform.getModVersion();
        loadData();
    }

    private static void loadData() {
        CoreData.loadData();
    }

    public static void onServerStarted() {
        try {
            CoreEngine.GRID_IDENTITIES.initialize(AE2Controller.serverPlatform.getWorldDirectory());
        } catch (IOException e) {
            LOG.error("Failed to load grid identities; grid requests remain unavailable", e);
        }
        serverRunning = true;
        loadIconPack();
        stopStorageTracking();
        ConfigSettings.ResourceTracking trackingSettings = Config.INSTANCE.resourceTracking;
        TerminalState liveState = new TerminalState();
        terminalState = liveState;
        TerminalStreams streams = new TerminalStreams(liveState, CoreEngine::getIconPack, context -> {
            if (!context.isLifecycleUnchanged()) return false;
            RequestContext authenticated = AuthService.authenticate(context.getExchange());
            return authenticated != null && authenticated.getPrincipal()
                .equals(context.getPrincipal());
        });
        terminalStreams = streams;
        StorageTrackingService service = new StorageTrackingService(
            new HsqlStorageHistoryStore(
                new File(AE2Controller.serverPlatform.getWorldDirectory(), "ae2webintegration/history/resources")
                    .toPath(),
                trackingSettings.historyRetentionDays,
                trackingSettings.historyMaxRowsPerGrid),
            trackingSettings.maxQueuedRows);
        storageCoordinator = new StorageTrackingCoordinator(service, GRID_IDENTITIES, trackingSettings, liveState);
        storageTracking = service;
        themes.close();
        themes = ThemeCatalog.load(
            Config.getConfigFile("themes")
                .toPath());
        AE2Controller.init();
        StartupHandler.logOpenAdminAccessWarning();
        maintainVersionChecker();
        StartupHandler.handleNotificationIntegration();
    }

    /**
     * Runs the queued synced requests on the server thread. The interface layer supplies nothing but the
     * platform's tick event - cadence, bounding and fault handling are decisions that belong here, not in
     * four copies of an event handler that no test can reach.
     */
    public static void onServerTick() {
        drainRequests(System::nanoTime);
        runPlanMaintenance(System.nanoTime());
        AE2Controller.itemIdentities.cleanUp();
        StorageTrackingCoordinator coordinator = storageCoordinator;
        TerminalStreams streams = terminalStreams;
        IAE ae = AE2Controller.AE2Interface;
        if (coordinator != null && ae != null) {
            long now = System.currentTimeMillis();
            if (streams != null) streams.onServerTick(ae, now, grid -> coordinator.touchViewer(grid, now));
            coordinator.tick(ae, now);
        }
        maintainVersionChecker();
    }

    private static void maintainVersionChecker() {
        if (!serverRunning) return;
        if (!Config.INSTANCE.general.checkForUpdates) {
            stopVersionChecker();
        } else if (versionChecker == null && modVersion != null) {
            try {
                VersionChecker checker = new VersionChecker(
                    new URL("https://raw.githubusercontent.com/kuba6000/AE2-Web-Integration/version/"),
                    modVersion,
                    AE2Controller.serverPlatform.getLoader(),
                    AE2Controller.serverPlatform.getMinecraftVersion());
                versionChecker = checker;
                checker.checkForUpdates();
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static void stopVersionChecker() {
        VersionChecker checker = versionChecker;
        if (checker != null) {
            checker.close();
            versionChecker = null;
        }
    }

    public static @Nullable ReleaseManifest.Release getAvailableUpdate() {
        VersionChecker checker = versionChecker;
        return checker == null ? null : checker.getAvailableUpdate();
    }

    public static boolean isUpdateSkipped(@NotNull ReleaseManifest.Release release) {
        VersionChecker checker = versionChecker;
        return checker != null && checker.isSkipped(release);
    }

    public static @NotNull ApiStatus skipUpdate(@NotNull String tag, long lifecycleGeneration) throws IOException {
        if (!AE2Controller.isCurrentHTTPLifecycle(lifecycleGeneration)) return ApiStatus.SERVER_STOPPING;
        VersionChecker checker = versionChecker;
        return checker == null ? ApiStatus.UPDATE_CHANGED : checker.skip(tag, lifecycleGeneration);
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
                    "Server-thread task {} failed",
                    task.getClass()
                        .getSimpleName(),
                    t);
                task.failIfPending(ApiStatus.INTERNAL_ERROR);
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
        stopTerminalStreams();
        AE2Controller.stopHTTPServer();
        stopStorageTracking();
        closeIconPack();
        // Authorization must not survive into the next world loaded in this JVM.
        themes.close();
        GRID_IDENTITIES.clear();
    }

    public static synchronized void onServerStopped() {
        serverRunning = false;
        stopVersionChecker();
        // Defensive when startup failed partway or a platform omits the earlier stopping callback.
        stopTerminalStreams();
        AE2Controller.stopHTTPServer();
        stopStorageTracking();
        closeIconPack();
        AE2Controller.clearWorldState();
        themes.close();
        AE2JobTracker.clearActiveJobs();
        GridData.clearRuntimeState();
        CoreEngine.GRID_IDENTITIES.clear();
        resetPlanMaintenance();
    }

    public static @Nullable String getModVersion() {
        return modVersion;
    }

    /** Detached data and history access for HTTP workers. Null outside an active server lifecycle. */
    public static @Nullable StorageTrackingService getStorageTracking() {
        return storageTracking;
    }

    /** Authorized terminal reads renew the shared native observation lease on the game thread. */
    public static @Nullable StorageSnapshot observeStorage(@NotNull IAEGrid grid) {
        return storageCoordinator == null ? null
            : storageCoordinator.observeForViewer(grid, System.currentTimeMillis());
    }

    public static void prepareTerminal(@NotNull IAEGrid grid) {
        StorageTrackingCoordinator coordinator = storageCoordinator;
        if (coordinator != null) coordinator.prepareForViewer(grid, System.currentTimeMillis());
    }

    public static @Nullable TerminalState getTerminalState() {
        return terminalState;
    }

    public static @Nullable TerminalStreams getTerminalStreams() {
        return terminalStreams;
    }

    private static void stopTerminalStreams() {
        TerminalStreams previousStreams = terminalStreams;
        terminalStreams = null;
        terminalState = null;
        if (previousStreams != null) previousStreams.close();
    }

    private static void stopStorageTracking() {
        stopTerminalStreams();
        if (storageCoordinator != null) {
            storageCoordinator.close();
            storageCoordinator = null;
        }
        StorageTrackingService previous = storageTracking;
        storageTracking = null;
        if (previous != null) previous.close();
    }

    /** Immutable metadata is safe to read on either thread; page streams lease the archive until closed. */
    public static @Nullable IconPack getIconPack() {
        return iconPack;
    }

    public static @NotNull ThemeCatalog getThemes() {
        return themes;
    }

    private static void loadIconPack() {
        closeIconPack();
        IServerPlatform platform = AE2Controller.serverPlatform;
        File directory = Config.getConfigFile("");
        File[] packs = directory.listFiles(
            file -> file.isFile() && file.getName()
                .endsWith(".ae2wi-icons"));
        if (packs == null) {
            LOG.error("Cannot list icon packs in {}. Terminal remains available without icons.", directory);
            return;
        }
        if (packs.length == 0) return;
        if (packs.length > 1) {
            Arrays.sort(packs);
            LOG.error(
                "Multiple icon packs found: {}. Keep only one .ae2wi-icons file and restart the server. "
                    + "Terminal remains available without icons.",
                Arrays.toString(packs));
            return;
        }
        File file = packs[0];
        try {
            iconPack = IconPack.open(
                file.toPath(),
                platform.getMinecraftVersion(),
                platform.getLoader(),
                platform.getIconPackCompatibilityVersion());
        } catch (IOException e) {
            LOG.error(
                "Cannot load icon pack {}. Terminal remains available without icons; replace the pack while stopped.",
                file,
                e);
        }
    }

    private static void closeIconPack() {
        IconPack previous = iconPack;
        iconPack = null;
        if (previous != null) {
            try {
                previous.close();
            } catch (IOException e) {
                LOG.error("Cannot close icon pack", e);
            }
        }
    }
}
