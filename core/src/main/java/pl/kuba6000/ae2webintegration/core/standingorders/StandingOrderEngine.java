package pl.kuba6000.ae2webintegration.core.standingorders;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.cpu.CpuReader;
import pl.kuba6000.ae2webintegration.core.grid.GridFilter;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.CraftingRequesterContext;
import pl.kuba6000.ae2webintegration.core.tracking.StockObservationListener;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;

/**
 * Runs the stored standing orders on the server thread: evaluates each network's orders whenever a complete stock
 * read of it arrives, calculates and submits crafting jobs and follows them until they leave their CPU.
 * <p>
 * Polling running calculations only checks readiness; completed calculations and queued orders share an evaluation
 * budget, checked between orders. A single native operation can exceed it. The native state of a network
 * (availability, the settings switches, the stock lease) is
 * read on demand: when a stock read arrives, when a command concerns the network, before an order is evaluated, and
 * by a sweep every 5 s that notices changes nothing else would. A network that is only briefly unavailable blocks
 * starting and submitting while it is away and loses nothing; everything it had read or calculated is dropped only
 * when the native network object was replaced, when it was switched off, or when no stock read arrived for longer
 * than its sample interval.
 * <p>
 * Everything except {@link #post}, {@link #postDeletion}, {@link #requestRun} and {@link #snapshot} is server-thread
 * only; those four are the boundary to HTTP threads and never touch engine state. The engine creates itself as the
 * coordinator's stock listener and keeps a stock lease for every network with an enabled order whose stock tracking is
 * off. {@link #close} cancels every calculation and clears all state.
 */
public final class StandingOrderEngine implements StockObservationListener, AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final long BACKOFF_FIRST_MILLIS = SECONDS.toMillis(30);
    private static final long BACKOFF_MAX_MILLIS = MINUTES.toMillis(10);
    /** How often every network is looked at even when nothing happened to it. */
    public static final long SWEEP_MILLIS = SECONDS.toMillis(5);
    /** A stock read may arrive this late after its interval before the stock it delivered is considered stale. */
    private static final long STALE_GRACE_MILLIS = SECONDS.toMillis(30);
    /** Missing ingredients published with a blocked order, largest first. */
    private static final int MAX_MISSING = 5;

    private final StorageTrackingCoordinator coordinator;
    private final GridIdentityRegistry identities;
    private final Supplier<ConfigSettings.StandingOrders> settings;
    private final Supplier<Map<ServerCapability, Boolean>> capabilities;
    private final LongSupplier nanoClock;

    private final Queue<Command> inbox = new ConcurrentLinkedQueue<>();
    private final Map<StableKey, StandingOrderSnapshot> snapshots = new ConcurrentHashMap<>();

    private final Map<StableKey, GridState> grids = new LinkedHashMap<>();
    private final Map<StableKey, OrderState> orders = new HashMap<>();
    private final Set<OrderState> work = new LinkedHashSet<>();
    private final Set<OrderState> ready = new LinkedHashSet<>();
    private final Set<GridState> changedDefinitions = new LinkedHashSet<>();
    private final PriorityQueue<Timer> timers = new PriorityQueue<>(Comparator.comparingLong(Timer::at));
    private final Set<OrderState> calculating = new LinkedHashSet<>();
    private final Map<StableKey, CpuView> cpuViews = new HashMap<>();
    private ConfigSettings.StandingOrders config;
    private long epoch;
    private long nextSweep;
    private @Nullable Map<ServerCapability, Boolean> tickCapabilities;
    private @Nullable String lastTickFailure;

    /**
     * @param coordinator  supplies stock reads; the engine registers as its listener
     * @param settings     read every tick, so a reloaded configuration applies at once
     * @param capabilities server capabilities, read at most once per tick
     * @param nanoClock    measures the per-tick budget
     */
    public StandingOrderEngine(@NotNull StorageTrackingCoordinator coordinator,
        @NotNull GridIdentityRegistry identities, @NotNull Supplier<ConfigSettings.StandingOrders> settings,
        @NotNull Supplier<Map<ServerCapability, Boolean>> capabilities, @NotNull LongSupplier nanoClock) {
        this.coordinator = coordinator;
        this.identities = identities;
        this.settings = settings;
        this.capabilities = capabilities;
        this.nanoClock = nanoClock;
        config = settings.get();
        coordinator.setStockListener(this);
    }

    // ----- Boundary to other threads -----

    // The engine applies posts in arrival order and never consults the store, so a caller must post each order's
    // changes in the order the store committed them; StandingOrderEditor does this for HTTP changes.

    /** Adds an order or replaces its definition; a stored order that cannot be decoded is posted as invalid. */
    public void post(@NotNull StableKey grid, @NotNull StandingOrderStore.Entry entry) {
        inbox.add(new Define(grid, entry));
    }

    /** Removes an order and cancels its calculation. */
    public void postDeletion(@NotNull StableKey grid, @NotNull StableKey order) {
        inbox.add(new Delete(grid, order));
    }

    /**
     * Asks for a check of the order now: its cooldown and backoff are skipped and its network is read once more. The
     * usual conditions still decide whether anything is crafted.
     */
    public void requestRun(@NotNull StableKey grid, @NotNull StableKey order) {
        inbox.add(new RunNow(grid, order));
    }

    /** Latest published state of the order; null before the engine evaluated it once, or when it does not exist. */
    public @Nullable StandingOrderSnapshot snapshot(@NotNull StableKey order) {
        return snapshots.get(order);
    }

    // ----- Stock feed (server thread, called by the coordinator) -----

    @Override
    public void stockObserved(@NotNull StableKey grid, @NotNull StorageObservation observation) {
        GridState state = grids.get(grid);
        // A network that was not synced yet may be fed already; the first sync drops what turns out to be unfed.
        if (state == null || !state.hasEnabledOrder || !state.fed && state.availability != null) return;
        Map<StableKey, Long> quantities = new HashMap<>();
        for (StorageObservation.Row row : observation.rows()) {
            if (state.referenced.contains(row.itemKey())) quantities.put(row.itemKey(), row.quantity());
        }
        // A complete read lists every stored and craftable resource, so a referenced one that is absent holds zero.
        for (StableKey resource : state.referenced) quantities.putIfAbsent(resource, 0L);
        state.stockGeneration++;
        state.baseline = new StockBaseline(observation.observedAt(), quantities);
        state.stockFailed = false;
        state.readWanted = false;
        state.pending = true;
    }

    @Override
    public void stockInvalidated(@NotNull StableKey grid) {
        GridState state = grids.get(grid);
        if (state == null || !state.hasEnabledOrder) return;
        state.baseline = null;
        state.stockFailed = true;
        state.readWanted = true;
        state.pending = true;
    }

    // ----- Tick (server thread) -----

    /**
     * Applies the posted changes now, so that a stock read the coordinator takes before {@link #tick} in the same
     * server tick already finds the orders it is for, as at server start.
     */
    public void applyPosted(long nowMillis) {
        guarded("inbox", () -> drainInbox(nowMillis));
    }

    /**
     * Applies posted changes, follows calculations and evaluates what is due within the configured budget. Call after
     * {@link StorageTrackingCoordinator#tick} with the same time.
     */
    public void tick(long nowMillis) {
        guarded("settings", () -> config = settings.get());
        tickCapabilities = null;
        epoch++;
        guarded("inbox", () -> drainInbox(nowMillis));
        boolean sweep = nowMillis >= nextSweep;
        if (sweep) nextSweep = nowMillis + SWEEP_MILLIS;
        for (GridState grid : grids.values()) {
            if (sweep || grid.pending) guarded("grid sync", () -> syncGrid(grid, nowMillis));
        }
        guarded("calculations", () -> pollCalculations(nowMillis));
        guarded("timers", () -> fireTimers(nowMillis));
        guarded("evaluation", () -> runWork(nowMillis));
        cpuViews.clear();
    }

    /**
     * The server tick rethrows whatever escapes it, so a failure in one phase must neither stop the server nor skip
     * the other phases. Each distinct failure is logged once until a different one occurs.
     */
    private void guarded(String phase, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            String cause = phase + ": " + describe(failure);
            if (!cause.equals(lastTickFailure)) LOG.error("Standing orders failed in {}", cause, failure);
            lastTickFailure = cause;
        }
    }

    /** Cancels every calculation, releases all leases and forgets every order. */
    @Override
    public void close() {
        for (OrderState order : orders.values()) {
            dropCalculation(order);
            order.removed = true;
        }
        for (GridState grid : grids.values()) coordinator.releaseStock(grid.key);
        coordinator.setStockListener(null);
        inbox.clear();
        grids.clear();
        orders.clear();
        work.clear();
        ready.clear();
        changedDefinitions.clear();
        timers.clear();
        calculating.clear();
        cpuViews.clear();
        snapshots.clear();
    }

    private void drainInbox(long now) {
        Command command;
        while ((command = inbox.poll()) != null) {
            Command posted = command;
            // One bad command must not hold back the ones behind it.
            guarded("command", () -> apply(posted, now));
        }
        // On startup many definitions arrive together. Rebuild each affected network's demand once,
        // before the coordinator can deliver its next stock observation.
        for (GridState grid : changedDefinitions) {
            grid.refresh();
            if (!grid.hasEnabledOrder) stopStockFeed(grid);
            else if (grid.availability == Availability.ACTIVE && grid.baseline != null
                && !grid.baseline.covers(grid.referenced)) {
                    coordinator.requestStockRead(grid.key);
                }
        }
        changedDefinitions.clear();
    }

    private void apply(Command command, long now) {
        if (command instanceof Define define) define(define.grid(), define.entry(), now);
        else if (command instanceof Delete delete) delete(delete.grid(), delete.order());
        else runNow(((RunNow) command).grid(), ((RunNow) command).order());
    }

    private void define(StableKey gridKey, StandingOrderStore.Entry entry, long now) {
        GridState grid = grids.computeIfAbsent(gridKey, GridState::new);
        OrderState order = orders.get(entry.key());
        if (order == null) {
            order = new OrderState(entry.key(), gridKey);
            orders.put(entry.key(), order);
            grid.orders.add(order);
        } else if (renamedOnly(order, entry)) {
            // Nothing the engine evaluates changed, so a running calculation and the failure state stay as they are.
            order.definition = ((StandingOrderStore.Valid) entry).definition();
            return;
        } else {
            cancelCalculation(order, now);
        }
        order.ordinal = entry.ordinal();
        // An edit may remove the cause of a failure, so it must not wait for the old backoff.
        order.failures = 0;
        order.backoffUntil = 0;
        StandingOrderDefinition previous = order.definition;
        if (entry instanceof StandingOrderStore.Valid valid) {
            order.definition = valid.definition();
            order.triggerResources.clear();
            GridState.collect(
                valid.definition()
                    .trigger(),
                order.triggerResources);
        } else {
            order.definition = null;
            order.triggerResources.clear();
        }
        if (previous == null || order.definition == null
            || !previous.action()
                .resource()
                .equals(
                    order.definition.action()
                        .resource())) {
            order.forgetJob();
            if (order.status == StandingOrderStatus.CRAFTING || order.status == StandingOrderStatus.CRAFTING_LONG
                || order.status == StandingOrderStatus.COOLDOWN) {
                setStatus(order, StandingOrderStatus.IDLE, null, now);
            }
        }
        changedDefinitions.add(grid);
        grid.pending = true;
        enqueue(order);
    }

    private static boolean renamedOnly(OrderState order, StandingOrderStore.Entry entry) {
        StandingOrderDefinition previous = order.definition;
        if (previous == null || !(entry instanceof StandingOrderStore.Valid valid)) return false;
        StandingOrderDefinition next = valid.definition();
        return order.ordinal == entry.ordinal() && new StandingOrderDefinition(
            next.name(),
            previous.enabled(),
            previous.trigger(),
            previous.action(),
            previous.cooldownSeconds()).equals(next);
    }

    private void delete(StableKey gridKey, StableKey orderKey) {
        OrderState order = orders.get(orderKey);
        if (order == null || !order.grid.equals(gridKey)) return;
        dropCalculation(order);
        order.removed = true;
        ready.remove(order);
        work.remove(order);
        orders.remove(orderKey);
        snapshots.remove(orderKey);
        timers.removeIf(timer -> timer.order() == order);
        GridState grid = grids.get(gridKey);
        grid.orders.remove(order);
        if (grid.orders.isEmpty()) {
            coordinator.releaseStock(gridKey);
            grids.remove(gridKey);
            changedDefinitions.remove(grid);
            cpuViews.remove(gridKey);
        } else {
            changedDefinitions.add(grid);
            grid.pending = true;
        }
    }

    private void runNow(StableKey gridKey, StableKey orderKey) {
        OrderState order = orders.get(orderKey);
        GridState grid = grids.get(gridKey);
        if (order == null || grid == null || !order.grid.equals(gridKey)) return;
        if (order.definition == null || !order.definition.enabled()) return;
        order.manualRunGeneration = grid.stockGeneration;
        order.backoffUntil = 0;
        order.cooldownUntil = 0;
        // Posted before the tick, Run may follow a switch change that the last sync could not have seen.
        if (grid.availability == Availability.ACTIVE) coordinator.requestStockRead(gridKey);
        else {
            grid.pending = true;
            grid.readWanted = true;
        }
    }

    /**
     * Reads the native state of the network once per tick at most, however many things ask for it: availability, the
     * switches and the stock lease. Orders are queued again when anything they depend on changed.
     */
    private void ensureSynced(GridState grid, long now) {
        if (grid.syncedEpoch != epoch) syncGrid(grid, now);
    }

    /**
     * Follows the network's availability, its switches and its stock lease. Only a switched-off network, a replaced
     * native network and stock that is older than its feed allows end what the network's orders hold; a network
     * that is merely unavailable keeps everything and only blocks starting and submitting while it is away.
     */
    private void syncGrid(GridState grid, long now) {
        grid.syncedEpoch = epoch;
        boolean wake = grid.pending;
        grid.pending = false;
        IAEGrid live = identities.getGrid(grid.key);
        GridPersistentData data = identities.getPersistentData(grid.key);
        GridSettingsData gridSettings = data == null ? null : data.getSettings();
        Availability availability;
        if (gridSettings == null) availability = Availability.UNAVAILABLE;
        else if (!config.enabled || !gridSettings.isStandingOrdersEnabled()) availability = Availability.SWITCHED_OFF;
        else if (live == null || !isUsable(live)) availability = Availability.UNAVAILABLE;
        else availability = Availability.ACTIVE;
        if (gridSettings != null) grid.intervalMillis = SECONDS.toMillis(gridSettings.getStockSampleIntervalSeconds());
        boolean replaced = availability == Availability.ACTIVE && grid.everSeen && grid.seen.get() != live;
        boolean switchedOff = availability == Availability.SWITCHED_OFF
            && grid.availability != Availability.SWITCHED_OFF;
        boolean stale = grid.baseline != null
            && (isStale(grid, grid.baseline, now) || coordinator.stockTopologyChanged(grid.key));
        if (replaced || switchedOff || stale) {
            // Whatever was calculated or read belongs to a network state that no longer exists.
            for (OrderState order : grid.orders) cancelCalculation(order, now);
            grid.baseline = null;
            grid.stockFailed = false;
            grid.readWanted = true;
            wake = true;
        }
        if (availability == Availability.ACTIVE && (replaced || !grid.everSeen)) {
            grid.seen = new WeakReference<>(live);
            grid.everSeen = true;
        }
        if (availability != grid.availability) wake = true;
        grid.availability = availability;
        if (!grid.hasEnabledOrder || gridSettings == null || availability == Availability.SWITCHED_OFF) {
            if (grid.baseline != null) wake = true;
            stopStockFeed(grid);
        } else {
            maintainStockFeed(grid, gridSettings.isStorageTracked(), now);
        }
        if (wake) {
            for (OrderState order : grid.orders) {
                if (order.definition != null && order.definition.enabled()) enqueue(order);
            }
        }
    }

    /** Use scheduled history samples when possible; otherwise lease a feed just for the enabled orders. */
    private void maintainStockFeed(GridState grid, boolean tracked, long now) {
        boolean lease = !tracked;
        if (lease != grid.leased) {
            grid.leased = lease;
            if (lease) coordinator.leaseStock(grid.key);
            else coordinator.releaseStock(grid.key);
        }
        if (!grid.fed && (grid.baseline == null || isStale(grid, grid.baseline, now))) grid.readWanted = true;
        grid.fed = true;
        if (grid.readWanted && grid.availability == Availability.ACTIVE) {
            grid.readWanted = false;
            coordinator.requestStockRead(grid.key);
        }
    }

    /** Releases the engine's extra reads even when normal tracking, rather than a lease, supplies its stock. */
    private void stopStockFeed(GridState grid) {
        coordinator.releaseStock(grid.key);
        grid.leased = false;
        grid.fed = false;
        grid.baseline = null;
        grid.readWanted = false;
    }

    /** Whether the network's stock feed should have delivered a newer read by now. */
    private static boolean isStale(GridState grid, StockBaseline baseline, long now) {
        return now - baseline.observedAt > grid.intervalMillis + STALE_GRACE_MILLIS;
    }

    private static boolean isUsable(IAEGrid grid) {
        try {
            return GridFilter.isUsable(grid);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void pollCalculations(long now) {
        long limit = MINUTES.toMillis(config.maxCalculationMinutes);
        for (OrderState order : calculating) {
            if (ready.contains(order)) continue;
            Future<IAECraftingJob> future = order.calculation;
            if (future != null && (future.isDone() || now - order.calculationStartedAt >= limit)) {
                work.remove(order);
                ready.add(order);
            }
        }
    }

    /** Handles a ready calculation inside the same budget as its order's guards and submission. */
    private boolean completeCalculation(OrderState order, GridState grid, long now) {
        Future<IAECraftingJob> future = order.calculation;
        if (future == null) return true;
        ensureSynced(grid, now);
        future = order.calculation;
        if (future == null) return true;
        if (!future.isDone()) {
            if (now - order.calculationStartedAt < MINUTES.toMillis(config.maxCalculationMinutes)) return true;
            LOG.warn("Calculation of standing order {} exceeded {} minutes", order.key, config.maxCalculationMinutes);
            dropCalculation(order);
            fail(order, StandingOrderStatus.SUBMIT_FAILED, Cause.of(StandingOrderReason.CALCULATION_TIMED_OUT), now);
            if (grid.activeGrid() == null) setStatus(order, StandingOrderStatus.GRID_UNAVAILABLE, null, now);
            return false;
        }
        releaseCalculation(order, grid);
        try {
            IAECraftingJob job = future.get();
            if (job.web$isSimulation() && !order.simulationAllowed) {
                fail(order, StandingOrderStatus.BLOCKED_MISSING, Cause.missing(missingIngredients(job, grid)), now);
                if (grid.activeGrid() == null) setStatus(order, StandingOrderStatus.GRID_UNAVAILABLE, null, now);
                return false;
            }
            order.job = job;
            return true;
        } catch (ExecutionException | RuntimeException failure) {
            LOG.warn("Calculation of standing order {} failed", order.key, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
        }
        fail(order, StandingOrderStatus.SUBMIT_FAILED, Cause.of(StandingOrderReason.CALCULATION_FAILED), now);
        if (grid.activeGrid() == null) setStatus(order, StandingOrderStatus.GRID_UNAVAILABLE, null, now);
        return false;
    }

    /**
     * The largest shortfalls, using the same native summary as the crafting plan endpoint. Legacy summaries simulate
     * inventory extraction for each plan entry. This runs once per blocked calculation; it does not schedule a stock
     * sample, but one large summary can exceed the evaluation budget.
     */
    private static List<StandingOrderSnapshot.MissingIngredient> missingIngredients(IAECraftingJob job,
        @Nullable GridState grid) {
        IAEGrid live = grid == null ? null : grid.activeGrid();
        if (live == null) return Collections.emptyList();
        // Keep only five native entries, ordered by shortfall. Equal quantities retain summary order.
        List<ICraftingPlanSummaryEntry> largest = new ArrayList<>(MAX_MISSING);
        try {
            for (ICraftingPlanSummaryEntry entry : job.web$generateSummary(live)
                .web$getEntries()) {
                long amount = entry.web$getMissingAmount();
                if (amount <= 0) continue;
                int index = 0;
                while (index < largest.size() && largest.get(index)
                    .web$getMissingAmount() >= amount) index++;
                if (index == MAX_MISSING) continue;
                if (largest.size() == MAX_MISSING) largest.remove(MAX_MISSING - 1);
                largest.add(index, entry);
            }
            List<StandingOrderSnapshot.MissingIngredient> missing = new ArrayList<>(largest.size());
            for (ICraftingPlanSummaryEntry entry : largest) {
                IAEKey resource = entry.web$getWhat();
                missing.add(
                    new StandingOrderSnapshot.MissingIngredient(
                        resource.web$getKey(),
                        resource.web$getDisplayName(),
                        entry.web$getMissingAmount()));
            }
            return Collections.unmodifiableList(missing);
        } catch (RuntimeException unreadable) {
            LOG.warn("The missing ingredients of a standing order calculation could not be read", unreadable);
            return Collections.emptyList();
        }
    }

    private void fireTimers(long now) {
        Timer timer;
        while ((timer = timers.peek()) != null && timer.at() <= now) {
            timers.poll();
            OrderState order = timer.order();
            if (!order.removed && order.wakeAt == timer.at()) {
                order.wakeAt = 0;
                enqueue(order);
            }
        }
    }

    /** Evaluates queued orders in arrival order until the tick budget is spent; at least one is always handled. */
    private void runWork(long now) {
        long deadline = nanoClock.getAsLong() + MILLISECONDS.toNanos(config.tickBudgetMillis);
        while (!ready.isEmpty() || !work.isEmpty()) {
            Iterator<OrderState> queue = (ready.isEmpty() ? work : ready).iterator();
            OrderState order = queue.next();
            queue.remove();
            evaluate(order, now);
            if (nanoClock.getAsLong() >= deadline) break;
        }
    }

    // ----- Evaluation (server thread) -----

    private void evaluate(OrderState order, long now) {
        if (order.removed) return;
        GridState grid = grids.get(order.grid);
        try {
            if (completeCalculation(order, grid, now)) evaluateChecked(order, grid, now);
        } catch (RuntimeException failure) {
            LOG.warn("Standing order {} failed unexpectedly", order.key, failure);
            cancelCalculation(order, now);
            fail(order, StandingOrderStatus.SUBMIT_FAILED, Cause.of(StandingOrderReason.UNEXPECTED_ERROR), now);
        }
        publish(order, grid);
    }

    private void evaluateChecked(OrderState order, GridState grid, long now) {
        StandingOrderDefinition definition = order.definition;
        if (definition == null) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, Cause.of(StandingOrderReason.INVALID_DEFINITION), now);
            return;
        }
        if (!definition.enabled()) {
            // Cooldown and backoff end with the order's activity, but a job it started may still finish while it is
            // off: enabling it again must still wait for a read taken after that job left its CPU.
            cancelCalculation(order, now);
            order.cooldownUntil = 0;
            order.backoffUntil = 0;
            order.failures = 0;
            if (order.craftingSince != 0) order.disabledWhileCrafting = true;
            setStatus(order, StandingOrderStatus.DISABLED, null, now);
            return;
        }
        ensureSynced(grid, now);
        IAEGrid live = grid.activeGrid();
        if (live == null) {
            if (grid.availability == Availability.SWITCHED_OFF) {
                cancelCalculation(order, now);
                setStatus(order, StandingOrderStatus.SWITCHED_OFF, null, now);
            } else {
                // A network that is away only blocks: a running calculation, a finished plan and a job keep going.
                setStatus(order, StandingOrderStatus.GRID_UNAVAILABLE, null, now);
            }
            return;
        }
        if (order.calculation != null) {
            checkCalculation(order, grid, live, definition, now);
            return;
        }
        IAECraftingJob plan = order.job;
        order.job = null;
        if (plan == null && order.craftingSince != 0) follow(order, grid, live, definition, now);
        else start(order, grid, live, definition, plan, now);
    }

    /** A running calculation continues unless a newer stock read shows that its trigger no longer holds. */
    private void checkCalculation(OrderState order, GridState grid, IAEGrid live, StandingOrderDefinition definition,
        long now) {
        StockBaseline baseline = grid.baseline;
        if (baseline != null && baseline.observedAt > order.calculationBaselineAt) {
            order.calculationBaselineAt = baseline.observedAt;
            if (definition.trigger()
                .evaluate(new Input(grid, live, baseline)) == Truth.FALSE) {
                cancelCalculation(order, now);
                return;
            }
        }
        setStatus(order, StandingOrderStatus.CALCULATING, null, now);
    }

    /** A running job: report how long it has been going and notice when it left its CPU. */
    private void follow(OrderState order, GridState grid, IAEGrid live, StandingOrderDefinition definition, long now) {
        boolean disabledWhileCrafting = order.disabledWhileCrafting;
        order.disabledWhileCrafting = false;
        if (cpuView(grid, live).finalOutputs.contains(
            definition.action()
                .resource())) {
            enterCrafting(order, now);
            return;
        }
        // A job that ended while its order was off has no cooldown to serve; only the stale-data guard applies, and
        // the read it waits for is asked for instead of left to the next sample.
        if (disabledWhileCrafting) coordinator.requestStockRead(grid.key);
        long cooldown = disabledWhileCrafting ? 0 : SECONDS.toMillis(definition.cooldownSeconds());
        order.craftingSince = 0;
        order.settledAt = now;
        order.cooldownUntil = now + cooldown;
        setStatus(order, cooldown > 0 ? StandingOrderStatus.COOLDOWN : StandingOrderStatus.IDLE, null, now);
        start(order, grid, live, definition, null, now);
    }

    /**
     * Runs the guards in order and either begins a calculation or, when {@code plan} is the finished calculation of
     * this order, checks it again against the latest data and submits it. A plan that fails any guard is dropped.
     */
    private void start(OrderState order, GridState grid, IAEGrid live, StandingOrderDefinition definition,
        @Nullable IAECraftingJob plan, long now) {
        // Another order's edit or a timer can wake this one before a throttled manual read arrives.
        if (order.manualRunGeneration == grid.stockGeneration) {
            setStatus(order, StandingOrderStatus.WAITING_FOR_STOCK, null, now);
            return;
        }
        // Gated on the time, not the status: an outage or a disable in between must not end the cooldown early.
        if (now < order.cooldownUntil) {
            setStatus(order, StandingOrderStatus.COOLDOWN, null, now);
            schedule(order, order.cooldownUntil);
            return;
        }
        if (order.status == StandingOrderStatus.COOLDOWN) setStatus(order, StandingOrderStatus.IDLE, null, now);
        StockBaseline baseline = grid.baseline;
        if (baseline == null && grid.stockFailed) {
            waitForStock(order, StandingOrderReason.READ_FAILED, now);
            return;
        }
        // A job that just left its CPU may still be missing from older reads, and would be ordered again.
        if (order.settledAt != 0 && (baseline == null || baseline.observedAt < order.settledAt)) {
            waitForStock(order, StandingOrderReason.WAITING_FRESH_READ_AFTER_JOB, now);
            return;
        }
        if (baseline == null) {
            waitForStock(order, StandingOrderReason.WAITING_FIRST_READ, now);
            return;
        }
        CraftAction action = definition.action();
        try {
            StandingOrderValidator.validateCapabilities(definition, capabilities());
        } catch (InvalidStandingOrderException unsupported) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, Cause.unsupported(unsupported.path()), now);
            return;
        }
        IAEKey resource;
        try {
            resource = AE2Controller.itemIdentities.resolve(action.resource());
        } catch (ItemIdentityRegistry.Ambiguous ambiguous) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, Cause.ambiguous(action.resource()), now);
            return;
        }
        if (resource == null) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, Cause.of(StandingOrderReason.UNKNOWN_RESOURCE), now);
            return;
        }
        for (StableKey triggerResource : order.triggerResources) {
            if (isAmbiguous(triggerResource)) {
                setStatus(order, StandingOrderStatus.UNAVAILABLE, Cause.ambiguous(triggerResource), now);
                return;
            }
        }
        Truth trigger = definition.trigger()
            .evaluate(new Input(grid, live, baseline));
        if (trigger == Truth.UNKNOWN) {
            waitForStock(order, StandingOrderReason.WAITING_READ_FOR_NEW_RESOURCES, now);
            return;
        }
        if (trigger == Truth.FALSE) {
            order.failures = 0;
            order.backoffUntil = 0;
            setStatus(order, StandingOrderStatus.IDLE, null, now);
            return;
        }
        if (now < order.backoffUntil) {
            setStatus(order, order.failureStatus, order.failureCause, now);
            schedule(order, order.backoffUntil);
            return;
        }
        IAECraftingGrid crafting = live.web$getCraftingGrid();
        // Order matters on legacy adapters: the crafting check rebuilds stale pattern state first.
        if (!crafting.web$isCurrentlyCraftable(resource)) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, Cause.of(StandingOrderReason.NOT_CRAFTABLE), now);
            return;
        }
        if (crafting.web$canEmitFor(resource)) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, Cause.of(StandingOrderReason.EMITTER_BACKED), now);
            return;
        }
        CpuView cpus = cpuView(grid, live);
        if (cpus.finalOutputs.contains(action.resource())) {
            enterCrafting(order, now);
            return;
        }
        ICraftingCPUCluster target = null;
        StableKey selected = action.cpu()
            .cpu();
        boolean cpuAvailable;
        if (selected == null) {
            cpuAvailable = cpus.eligibleIdle > 0;
        } else {
            target = cpus.find(selected);
            cpuAvailable = target != null && isEligible(target);
        }
        if (!cpuAvailable) {
            setStatus(
                order,
                StandingOrderStatus.WAITING_FOR_CPU,
                Cause.of(
                    selected == null ? StandingOrderReason.NO_IDLE_CPU : StandingOrderReason.SELECTED_CPU_UNAVAILABLE),
                now);
            return;
        }
        OptionalLong stored = baseline.stored(action.resource());
        if (!stored.isPresent()) {
            waitForStock(order, StandingOrderReason.WAITING_READ_FOR_NEW_RESOURCES, now);
            return;
        }
        OrderAmount amount = action.amount()
            .orderAmount(stored.getAsLong());
        if (amount.outcome() == OrderAmount.Outcome.NOTHING_TO_ORDER) {
            setStatus(order, StandingOrderStatus.IDLE, Cause.of(StandingOrderReason.NOTHING_TO_ORDER), now);
            return;
        }
        if (amount.outcome() == OrderAmount.Outcome.OVERFLOW) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, Cause.of(StandingOrderReason.AMOUNT_OVERFLOW), now);
            return;
        }
        if (plan != null) {
            // Orders only ever plan up to the batch limit, so a still positive need cannot be overshot by more.
            submit(order, definition, live, crafting, plan, target, now);
        } else {
            beginCalculation(order, grid, baseline, definition, live, crafting, resource, amount.amount(), now);
        }
    }

    private void waitForStock(OrderState order, StandingOrderReason reason, long now) {
        setStatus(order, StandingOrderStatus.WAITING_FOR_STOCK, Cause.of(reason), now);
    }

    private void beginCalculation(OrderState order, GridState grid, StockBaseline baseline,
        StandingOrderDefinition definition, IAEGrid live, IAECraftingGrid crafting, IAEKey resource, long amount,
        long now) {
        if (grid.inFlight >= config.maxCalculationsInFlightPerGrid) {
            setStatus(order, StandingOrderStatus.QUEUED, Cause.of(StandingOrderReason.CALCULATION_SLOTS_FULL), now);
            return;
        }
        order.calculation = crafting.web$beginCraftingJob(
            live,
            resource,
            amount,
            definition.action()
                .options());
        order.calculationStartedAt = now;
        order.calculationBaselineAt = baseline.observedAt;
        order.plannedAmount = amount;
        order.simulationAllowed = definition.action()
            .options()
            .ignoreMissing();
        grid.inFlight++;
        calculating.add(order);
        setStatus(order, StandingOrderStatus.CALCULATING, null, now);
    }

    private void submit(OrderState order, StandingOrderDefinition definition, IAEGrid live, IAECraftingGrid crafting,
        IAECraftingJob plan, @Nullable ICraftingCPUCluster target, long now) {
        String error;
        try (CraftingRequesterContext ignored = CraftingRequesterContext
            .openStandingOrder(order.key, definition.name())) {
            error = crafting.web$submitJob(plan, target, true, live);
        } catch (RuntimeException failure) {
            LOG.warn("Submitting the job of standing order {} failed", order.key, failure);
            fail(order, StandingOrderStatus.SUBMIT_FAILED, Cause.of(StandingOrderReason.SUBMIT_ERROR), now);
            return;
        }
        if (error != null) {
            fail(order, StandingOrderStatus.SUBMIT_FAILED, Cause.submitError(error), now);
            return;
        }
        // The submission changed the CPUs; later orders of this tick must not count on the earlier picture.
        cpuViews.remove(order.grid);
        order.lastSubmittedAmount = order.plannedAmount;
        order.failures = 0;
        order.backoffUntil = 0;
        enterCrafting(order, now);
    }

    private void enterCrafting(OrderState order, long now) {
        long threshold = MINUTES.toMillis(config.longRunningJobMinutes);
        // The job has been running since it was submitted or first seen, whatever the status showed in between.
        if (order.craftingSince == 0) order.craftingSince = now;
        if (now - order.craftingSince >= threshold) {
            setStatus(order, StandingOrderStatus.CRAFTING_LONG, null, now);
        } else {
            setStatus(order, StandingOrderStatus.CRAFTING, null, now);
            schedule(order, order.craftingSince + threshold);
        }
    }

    /** Records a failure and keeps the order from starting again until its exponentially growing backoff passed. */
    private void fail(OrderState order, StandingOrderStatus status, Cause cause, long now) {
        order.failures++;
        long delay = BACKOFF_FIRST_MILLIS << Math.min(order.failures - 1, 16);
        order.backoffUntil = now + Math.min(delay, BACKOFF_MAX_MILLIS);
        order.failureStatus = status;
        order.failureCause = cause;
        setStatus(order, status, cause, now);
        schedule(order, order.backoffUntil);
    }

    private void setStatus(OrderState order, StandingOrderStatus status, @Nullable Cause cause, long now) {
        if (order.status == status && Objects.equals(order.cause, cause)) return;
        order.status = status;
        order.cause = cause;
        order.changedAt = now;
    }

    private void publish(OrderState order, @Nullable GridState grid) {
        if (order.removed) return;
        Cause cause = order.cause;
        StandingOrderSnapshot next = new StandingOrderSnapshot(
            order.status,
            cause == null ? null : cause.reason(),
            cause == null ? null : cause.resource(),
            cause == null ? null : cause.path(),
            cause == null ? null : cause.submitError(),
            cause == null ? Collections.emptyList() : cause.missing(),
            order.changedAt,
            order.lastSubmittedAmount,
            nextCheck(order, grid),
            order.status == StandingOrderStatus.CRAFTING || order.status == StandingOrderStatus.CRAFTING_LONG
                ? order.craftingSince
                : 0);
        if (!next.equals(snapshots.get(order.key))) snapshots.put(order.key, next);
    }

    private long nextCheck(OrderState order, @Nullable GridState grid) {
        return switch (order.status) {
            case COOLDOWN -> order.cooldownUntil;
            case BLOCKED_MISSING, SUBMIT_FAILED -> order.backoffUntil;
            case CALCULATING, DISABLED, SWITCHED_OFF, GRID_UNAVAILABLE -> 0;
            default -> grid == null || grid.baseline == null ? 0 : grid.baseline.observedAt + grid.intervalMillis;
        };
    }

    // ----- Queues -----

    private void enqueue(OrderState order) {
        if (!order.removed && !ready.contains(order)) work.add(order);
    }

    private void schedule(OrderState order, long at) {
        if (order.wakeAt == at) return;
        order.wakeAt = at;
        timers.add(new Timer(at, order));
    }

    // ----- Calculations -----

    /** Drops a running calculation or a finished plan that was not submitted yet, without touching the status. */
    private void dropCalculation(OrderState order) {
        Future<IAECraftingJob> future = order.calculation;
        if (future != null) {
            if (!future.isDone()) future.cancel(true);
            releaseCalculation(order, grids.get(order.grid));
        }
        order.job = null;
    }

    /** Like {@link #dropCalculation}; an order that was calculating goes back to idle. */
    private void cancelCalculation(OrderState order, long now) {
        boolean calculating = order.calculation != null || order.job != null;
        dropCalculation(order);
        if (calculating) setStatus(order, StandingOrderStatus.IDLE, null, now);
    }

    private void releaseCalculation(OrderState order, @Nullable GridState grid) {
        order.calculation = null;
        calculating.remove(order);
        if (grid == null) return;
        grid.inFlight--;
        for (OrderState waiting : grid.orders) {
            if (waiting.status == StandingOrderStatus.QUEUED) enqueue(waiting);
        }
    }

    // ----- Reads of the network -----

    private @NotNull CpuView cpuView(GridState grid, IAEGrid live) {
        return cpuViews.computeIfAbsent(grid.key, key -> CpuView.read(live.web$getCraftingGrid()));
    }

    private @NotNull Map<ServerCapability, Boolean> capabilities() {
        Map<ServerCapability, Boolean> current = tickCapabilities;
        if (current == null) {
            current = capabilities.get();
            tickCapabilities = current;
        }
        return current;
    }

    private static boolean isAmbiguous(StableKey resource) {
        try {
            AE2Controller.itemIdentities.resolve(resource);
            return false;
        } catch (ItemIdentityRegistry.Ambiguous ambiguous) {
            return true;
        }
    }

    /** Standing orders submit as the network's web player, so only CPUs that accept player jobs are usable. */
    private static boolean isEligible(ICraftingCPUCluster cpu) {
        return !cpu.web$isBusy() && cpu.web$acceptsPlayerJobs();
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause()
            : failure;
        String message = cause.getMessage();
        return message == null ? cause.getClass()
            .getSimpleName() : message;
    }

    // ----- State -----

    private enum Availability {
        ACTIVE,
        SWITCHED_OFF,
        UNAVAILABLE
    }

    private interface Command {
    }

    @Desugar
    private record Define(StableKey grid, StandingOrderStore.Entry entry) implements Command {}

    @Desugar
    private record Delete(StableKey grid, StableKey order) implements Command {}

    @Desugar
    private record RunNow(StableKey grid, StableKey order) implements Command {}

    @Desugar
    private record Timer(long at, OrderState order) {}

    /** Why an order has its status, as published in the snapshot. */
    @Desugar
    private record Cause(StandingOrderReason reason, @Nullable StableKey resource, @Nullable String path,
        @Nullable String submitError, List<StandingOrderSnapshot.MissingIngredient> missing) {

        private static Cause of(StandingOrderReason reason) {
            return new Cause(reason, null, null, null, Collections.emptyList());
        }

        private static Cause ambiguous(StableKey resource) {
            return new Cause(StandingOrderReason.AMBIGUOUS_RESOURCE, resource, null, null, Collections.emptyList());
        }

        private static Cause unsupported(String path) {
            return new Cause(StandingOrderReason.CAPABILITY_UNAVAILABLE, null, path, null, Collections.emptyList());
        }

        private static Cause submitError(String error) {
            return new Cause(StandingOrderReason.SUBMIT_REJECTED, null, null, error, Collections.emptyList());
        }

        private static Cause missing(List<StandingOrderSnapshot.MissingIngredient> missing) {
            return new Cause(StandingOrderReason.MISSING_INGREDIENTS, null, null, null, missing);
        }
    }

    /** Quantities of the resources the network's orders refer to, from one complete stock read. */
    @Desugar
    private record StockBaseline(long observedAt, Map<StableKey, Long> quantities) {

        private OptionalLong stored(StableKey resource) {
            Long quantity = quantities.get(resource);
            return quantity == null ? OptionalLong.empty() : OptionalLong.of(quantity);
        }

        private boolean covers(Set<StableKey> resources) {
            return quantities.keySet()
                .containsAll(resources);
        }
    }

    /** The crafting CPUs of a network as seen once in a tick. */
    private static final class CpuView {

        private final IAECraftingGrid crafting;
        private final Set<StableKey> finalOutputs = new HashSet<>();
        private int eligibleIdle;
        private @Nullable Map<StableKey, ICraftingCPUCluster> byKey;

        private CpuView(IAECraftingGrid crafting) {
            this.crafting = crafting;
        }

        private static CpuView read(IAECraftingGrid crafting) {
            CpuView view = new CpuView(crafting);
            for (ICraftingCPUCluster cpu : crafting.web$getCPUs()) {
                if (cpu.web$isBusy()) {
                    IAEGenericStack output = cpu.web$getFinalOutput();
                    StableKey key = output == null ? null
                        : output.web$what()
                            .web$getKey();
                    if (key != null) view.finalOutputs.add(key);
                } else if (cpu.web$acceptsPlayerJobs()) {
                    view.eligibleIdle++;
                }
            }
            return view;
        }

        private @Nullable ICraftingCPUCluster find(StableKey key) {
            if (byKey == null) byKey = CpuReader.list(crafting);
            return byKey.get(key);
        }
    }

    private final class Input implements ConditionInput {

        private final GridState grid;
        private final IAEGrid live;
        private final StockBaseline baseline;

        private Input(GridState grid, IAEGrid live, StockBaseline baseline) {
            this.grid = grid;
            this.live = live;
            this.baseline = baseline;
        }

        @Override
        public @NotNull OptionalLong storedAmount(@NotNull StableKey resource) {
            return baseline.stored(resource);
        }

        @Override
        public int eligibleIdleCpus() {
            return cpuView(grid, live).eligibleIdle;
        }
    }

    private static final class GridState {

        private final StableKey key;
        private final List<OrderState> orders = new ArrayList<>();
        private final Set<StableKey> referenced = new HashSet<>();
        private @Nullable Availability availability;
        /**
         * The usable native network last seen, to notice that it was replaced; weak so that an unloaded network is not
         * kept alive.
         */
        private WeakReference<IAEGrid> seen = new WeakReference<>(null);
        /** Whether a usable native network was seen at all; the first one is not a replacement. */
        private boolean everSeen;
        private @Nullable StockBaseline baseline;
        private boolean stockFailed;
        private long stockGeneration;
        private long intervalMillis;
        private boolean hasEnabledOrder;
        private boolean leased;
        private boolean fed;
        /** A stock read arrived or an order changed, so the network's state is read and its orders evaluated. */
        private boolean pending;
        /** The tick whose native read of the network is current. */
        private long syncedEpoch = -1;
        /** A read is wanted as soon as the network is fed and usable, because there is no usable stock. */
        private boolean readWanted;
        private int inFlight;

        private @Nullable IAEGrid activeGrid() {
            return availability == Availability.ACTIVE ? seen.get() : null;
        }

        private GridState(StableKey key) {
            this.key = key;
        }

        /** Recomputes what the orders need after the set of orders or a definition changed. */
        private void refresh() {
            orders.sort(
                Comparator.comparingInt((OrderState order) -> order.ordinal)
                    .thenComparing(order -> order.key.toString()));
            referenced.clear();
            hasEnabledOrder = false;
            for (OrderState order : orders) {
                StandingOrderDefinition definition = order.definition;
                if (definition == null || !definition.enabled()) continue;
                hasEnabledOrder = true;
                referenced.add(
                    definition.action()
                        .resource());
                referenced.addAll(order.triggerResources);
            }
        }

        private static void collect(Condition condition, Set<StableKey> resources) {
            if (condition instanceof Condition.StockBelow below) resources.add(below.resource());
            else if (condition instanceof Condition.StockAtLeast atLeast) resources.add(atLeast.resource());
            else if (condition instanceof Condition.All all) {
                for (Condition child : all.children()) collect(child, resources);
            } else if (condition instanceof Condition.Any any) {
                for (Condition child : any.children()) collect(child, resources);
            } else if (condition instanceof Condition.Not not) collect(not.child(), resources);
        }
    }

    private static final class OrderState {

        private final StableKey key;
        private final StableKey grid;
        private int ordinal;
        private @Nullable StandingOrderDefinition definition;
        private final Set<StableKey> triggerResources = new HashSet<>();
        private StandingOrderStatus status = StandingOrderStatus.IDLE;
        private @Nullable Cause cause;
        private long changedAt;
        private long manualRunGeneration = -1;
        private long craftingSince;
        /** The order was disabled while its job ran, so the cooldown of that job ended with its activity. */
        private boolean disabledWhileCrafting;
        private long lastSubmittedAmount;
        private long settledAt;
        private long cooldownUntil;
        private long backoffUntil;
        private int failures;
        private StandingOrderStatus failureStatus = StandingOrderStatus.SUBMIT_FAILED;
        private @Nullable Cause failureCause;
        private long wakeAt;
        private @Nullable Future<IAECraftingJob> calculation;
        private long calculationStartedAt;
        /** Observation time of the stock the calculation started from or was last compared with. */
        private long calculationBaselineAt;
        private @Nullable IAECraftingJob job;
        private long plannedAmount;
        private boolean simulationAllowed;
        private boolean removed;

        private OrderState(StableKey key, StableKey grid) {
            this.key = key;
            this.grid = grid;
        }

        /** The crafted resource changed, so a running job, cooldown and failures no longer belong to this order. */
        private void forgetJob() {
            craftingSince = 0;
            disabledWhileCrafting = false;
            settledAt = 0;
            cooldownUntil = 0;
            backoffUntil = 0;
            failures = 0;
        }
    }
}
