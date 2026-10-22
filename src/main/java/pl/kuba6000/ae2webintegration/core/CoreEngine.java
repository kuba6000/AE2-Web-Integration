package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.cpu.CpuLiveCoordinator;
import pl.kuba6000.ae2webintegration.core.cpu.CpuRegistry;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.live.LiveStreams;
import pl.kuba6000.ae2webintegration.core.notification.NotificationManager;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.themes.ThemeCatalog;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.crafting.CraftingHistoryService;
import pl.kuba6000.ae2webintegration.core.tracking.crafting.SqliteCraftingHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.SqliteStorageHistoryStore;
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
    private static volatile @Nullable WorldDatabase worldDatabase;
    private static @Nullable ScheduledExecutorService gridFlushScheduler;
    private static volatile @Nullable ResourcePinStore resourcePins;
    private static volatile @Nullable IconPack iconPack;
    private static volatile ThemeCatalog themes = new ThemeCatalog();
    private static volatile @Nullable StorageTrackingService storageTracking;
    private static volatile @Nullable CraftingHistoryService craftingHistory;
    private static volatile @Nullable StorageTrackingCoordinator storageCoordinator;
    private static volatile @Nullable LiveStreams liveStreams;
    private static volatile @Nullable TerminalRegistry terminalRegistry;
    private static volatile @Nullable CpuRegistry cpuRegistry;
    private static volatile @Nullable CpuLiveCoordinator cpuLive;

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

    /** Publishes reloaded data settings immediately; the world scheduler applies durable changes. */
    public static void applyDataConfig() {
        WorldDatabase database = worldDatabase;
        if (database != null) database.applyPolicy(Config.INSTANCE.resourceHistory, Config.INSTANCE.craftingHistory);
    }

    /** Applies HTTP/worker policy now; native observation settings are picked up on the next game tick. */
    public static void applyServerConfig() {
        AuthService.reloadHttpSettings();
        applyDataConfig();
        NotificationManager.reloadSettings();
    }

    public static void onServerStarted() {
        // Finish the previous world before binding any new identity or storage owner.
        onServerStopping();
        File databaseFile = new File(
            AE2Controller.serverPlatform.getWorldDirectory(),
            "ae2webintegration/grids.sqlite");
        WorldDatabase database = new WorldDatabase(
            databaseFile.toPath(),
            Config.INSTANCE.resourceHistory,
            Config.INSTANCE.craftingHistory);
        SqliteCraftingHistoryStore craftings = new SqliteCraftingHistoryStore(database);
        SqliteStorageHistoryStore history = new SqliteStorageHistoryStore(database);
        try {
            database.open();
            GRID_IDENTITIES.initialize(
                database,
                new File(AE2Controller.serverPlatform.getWorldDirectory(), "ae2webintegration/grid-identities.json")
                    .toPath());
            history.initialize();
            craftings.maintain();
        } catch (IOException | RuntimeException | LinkageError failure) {
            GRID_IDENTITIES.clear();
            try {
                database.close();
            } catch (IOException | RuntimeException | LinkageError closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof LinkageError linkageFailure) throw linkageFailure;
            throw new IllegalStateException("Failed to initialize world database: " + databaseFile, failure);
        }
        try {
            worldDatabase = database;
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "AE2Web-GridPersistence");
                thread.setDaemon(true);
                return thread;
            });
            gridFlushScheduler = scheduler;
            CraftingHistoryService craftingService = new CraftingHistoryService(craftings, scheduler);
            craftingHistory = craftingService;
            database.startGridFlush(scheduler, () -> {
                history.maintain();
                try {
                    craftingService.maintain();
                } catch (IOException failure) {
                    LOG.error("Crafting history maintenance failed; a later pass will retry", failure);
                }
            });
            resourcePins = new ResourcePinStore(database);
            serverRunning = true;
            loadIconPack();
            ConfigSettings.ResourceTracking trackingSettings = Config.INSTANCE.resourceTracking;
            LiveHub hub = new LiveHub();
            TerminalRegistry terminals = new TerminalRegistry(hub);
            terminalRegistry = terminals;
            CpuRegistry cpus = new CpuRegistry(hub);
            cpuRegistry = cpus;
            cpuLive = new CpuLiveCoordinator(cpus, GRID_IDENTITIES, trackingSettings);
            liveStreams = new LiveStreams(hub, GRID_IDENTITIES, getIconPack());
            StorageTrackingService service = new StorageTrackingService(history, trackingSettings.maxQueuedRows);
            storageTracking = service;
            storageCoordinator = new StorageTrackingCoordinator(service, GRID_IDENTITIES, trackingSettings, terminals);
            themes.close();
            themes = ThemeCatalog.load(
                Config.getConfigFile("themes")
                    .toPath());
            AE2Controller.init();
            StartupHandler.logOpenAdminAccessWarning();
            maintainVersionChecker();
            StartupHandler.handleNotificationIntegration();
        } catch (RuntimeException failure) {
            onServerStopping();
            LOG.error("World services failed during startup", failure);
        }
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
        IAE ae = AE2Controller.AE2Interface;
        if (coordinator != null && ae != null) {
            long now = System.currentTimeMillis();
            coordinator.applySettings(Config.INSTANCE.resourceTracking, now);
            coordinator.tick(ae, now);
        }
        CpuLiveCoordinator cpus = cpuLive;
        if (cpus != null) {
            long now = System.currentTimeMillis();
            cpus.applySettings(Config.INSTANCE.resourceTracking, now);
            cpus.tick(now);
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
        stopLiveStreams();
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
        stopLiveStreams();
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
    public static @Nullable ResourcePinStore getResourcePins() {
        return resourcePins;
    }

    public static @Nullable StorageTrackingService getStorageTracking() {
        return storageTracking;
    }

    /** Detached recent tracking only; HTTP readers neither capture native state nor query durable history. */
    public static @Nullable RecentResourceActivity.Summary recentActivity(@NotNull StableKey key) {
        StorageTrackingCoordinator coordinator = storageCoordinator;
        return coordinator == null ? null : coordinator.recent(key);
    }

    /** Authorized terminal reads renew the shared native observation lease on the game thread. */
    public static @Nullable StorageSnapshot observeStorage(@NotNull IAEGrid grid) {
        StorageTrackingCoordinator coordinator = storageCoordinator;
        return coordinator == null ? null : coordinator.observeForViewer(grid, System.currentTimeMillis());
    }

    public static void prepareTerminal(@NotNull IAEGrid grid) {
        StorageTrackingCoordinator coordinator = storageCoordinator;
        if (coordinator != null) coordinator.prepareForViewer(grid, System.currentTimeMillis());
    }

    public static @Nullable TerminalRegistry getTerminalRegistry() {
        return terminalRegistry;
    }

    public static @Nullable CpuRegistry getCpuRegistry() {
        return cpuRegistry;
    }

    /** Server-thread observation of viewed crafting CPUs, also fed by the native change notifications. */
    public static @Nullable CpuLiveCoordinator getCpuLive() {
        return cpuLive;
    }

    public static @Nullable LiveStreams getLiveStreams() {
        return liveStreams;
    }

    private static void stopLiveStreams() {
        LiveStreams previousStreams = liveStreams;
        liveStreams = null;
        terminalRegistry = null;
        cpuRegistry = null;
        cpuLive = null;
        if (previousStreams != null) previousStreams.close();
    }

    public static @Nullable CraftingHistoryService getCraftingHistory() {
        return craftingHistory;
    }

    private static void stopStorageTracking() {
        WorldDatabase database = worldDatabase;
        if (database != null) database.stopGridFlush();
        ScheduledExecutorService scheduler = gridFlushScheduler;
        gridFlushScheduler = null;
        if (scheduler != null) scheduler.shutdown();
        resourcePins = null;
        stopLiveStreams();
        StorageTrackingCoordinator coordinator = storageCoordinator;
        if (coordinator != null) {
            coordinator.close();
            storageCoordinator = null;
        }
        if (database != null) {
            try {
                database.finishGridIdentities();
            } catch (IOException failure) {
                LOG.error("Final grid identity flush failed; queued dependent writes will be rejected", failure);
            }
        }
        // Flush even newly retired identities before clearing admission and draining captured tails.
        GRID_IDENTITIES.clear();
        StorageTrackingService previous = storageTracking;
        storageTracking = null;
        worldDatabase = null;
        CraftingHistoryService completed = craftingHistory;
        craftingHistory = null;
        if (completed != null) {
            try {
                completed.close();
            } catch (IOException | RuntimeException failure) {
                LOG.error("Cannot finish writing crafting history", failure);
            }
        }
        if (previous != null) previous.close();
        else if (database != null) {
            try {
                database.close();
            } catch (IOException failure) {
                LOG.error("Cannot close world database", failure);
            }
        }

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
