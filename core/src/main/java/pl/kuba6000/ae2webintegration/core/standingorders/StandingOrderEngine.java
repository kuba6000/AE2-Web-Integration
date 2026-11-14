package pl.kuba6000.ae2webintegration.core.standingorders;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.CraftingRequesterContext;
import pl.kuba6000.ae2webintegration.core.tracking.StockObservationListener;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;

/**
 * Runs the stored standing orders on the server thread: evaluates each network's orders whenever a complete stock
 * read of it arrives, calculates and submits crafting jobs and follows them until they leave their CPU.
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

    private final StorageTrackingCoordinator coordinator;
    private final GridIdentityRegistry identities;
    private final Supplier<ConfigSettings.StandingOrders> settings;
    private final Supplier<Map<ServerCapability, Boolean>> capabilities;
    private final LongSupplier nanoClock;

    private final Queue<Command> inbox = new ConcurrentLinkedQueue<>();
    private final Map<StableKey, StandingOrderSnapshot> snapshots = new ConcurrentHashMap<>();

    private final Map<StableKey, GridState> grids = new LinkedHashMap<>();
    private final Map<StableKey, OrderState> orders = new HashMap<>();
    private final ArrayDeque<OrderState> work = new ArrayDeque<>();
    private final PriorityQueue<Timer> timers = new PriorityQueue<>(Comparator.comparingLong(Timer::at));
    private final List<OrderState> calculating = new ArrayList<>();
    private final Map<StableKey, CpuView> cpuViews = new HashMap<>();
    private ConfigSettings.StandingOrders config;
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
        if (state == null || !state.fed) return;
        Map<StableKey, Long> quantities = new HashMap<>();
        for (StorageObservation.Row row : observation.rows()) {
            if (state.referenced.contains(row.itemKey())) quantities.put(row.itemKey(), row.quantity());
        }
        // A complete read lists every stored and craftable resource, so a referenced one that is absent holds zero.
        for (StableKey resource : state.referenced) quantities.putIfAbsent(resource, 0L);
        state.baseline = new StockBaseline(observation.observedAt(), quantities);
        state.stockFailed = false;
        state.changed = true;
    }

    @Override
    public void stockReadFailed(@NotNull StableKey grid, @NotNull RuntimeException failure) {
        GridState state = grids.get(grid);
        if (state == null) return;
        state.baseline = null;
        state.stockFailed = true;
        state.changed = true;
    }

    // ----- Tick (server thread) -----

    /**
     * Applies posted changes, follows calculations and evaluates what is due within the configured budget. Call after
     * {@link StorageTrackingCoordinator#tick} with the same time.
     */
    public void tick(long nowMillis) {
        guarded("settings", () -> config = settings.get());
        cpuViews.clear();
        tickCapabilities = null;
        guarded("inbox", this::drainInbox);
        for (GridState grid : grids.values()) guarded("grid sync", () -> syncGrid(grid));
        guarded("calculations", () -> pollCalculations(nowMillis));
        guarded("timers", () -> fireTimers(nowMillis));
        guarded("evaluation", () -> runWork(nowMillis));
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
            cancelCalculation(order);
            order.removed = true;
        }
        for (GridState grid : grids.values()) {
            if (grid.leased) coordinator.releaseStock(grid.key);
        }
        coordinator.setStockListener(null);
        inbox.clear();
        grids.clear();
        orders.clear();
        work.clear();
        timers.clear();
        calculating.clear();
        cpuViews.clear();
        snapshots.clear();
    }

    private void drainInbox() {
        Command command;
        while ((command = inbox.poll()) != null) {
            Command posted = command;
            // One bad command must not hold back the ones behind it.
            guarded("command", () -> apply(posted));
        }
    }

    private void apply(Command command) {
        if (command instanceof Define define) define(define.grid(), define.entry());
        else if (command instanceof Delete delete) delete(delete.grid(), delete.order());
        else runNow(((RunNow) command).grid(), ((RunNow) command).order());
    }

    private void define(StableKey gridKey, StandingOrderStore.Entry entry) {
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
            cancelCalculation(order);
        }
        order.ordinal = entry.ordinal();
        // An edit may remove the cause of a failure, so it must not wait for the old backoff.
        order.failures = 0;
        order.backoffUntil = 0;
        StandingOrderDefinition previous = order.definition;
        if (entry instanceof StandingOrderStore.Valid valid) {
            order.definition = valid.definition();
            order.invalid = null;
            order.triggerResources.clear();
            GridState.collect(
                valid.definition()
                    .trigger(),
                order.triggerResources);
        } else {
            order.definition = null;
            order.triggerResources.clear();
            order.invalid = ((StandingOrderStore.Invalid) entry).failure();
        }
        if (previous == null || order.definition == null
            || !previous.action()
                .resource()
                .equals(
                    order.definition.action()
                        .resource())) {
            order.forgetJob();
        }
        grid.orders.sort(
            Comparator.comparingInt((OrderState o) -> o.ordinal)
                .thenComparing(o -> o.key.toString()));
        grid.refresh();
        if (grid.availability == Availability.ACTIVE && grid.baseline != null
            && !grid.baseline.covers(grid.referenced)) {
            coordinator.requestStockRead(grid.key);
        }
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
        cancelCalculation(order);
        order.removed = true;
        orders.remove(orderKey);
        snapshots.remove(orderKey);
        timers.removeIf(timer -> timer.order() == order);
        GridState grid = grids.get(gridKey);
        grid.orders.remove(order);
        if (grid.orders.isEmpty()) {
            if (grid.leased) coordinator.releaseStock(gridKey);
            grids.remove(gridKey);
            cpuViews.remove(gridKey);
        } else {
            grid.refresh();
        }
    }

    private void runNow(StableKey gridKey, StableKey orderKey) {
        OrderState order = orders.get(orderKey);
        GridState grid = grids.get(gridKey);
        if (order == null || grid == null || !order.grid.equals(gridKey)) return;
        if (order.definition == null || !order.definition.enabled() || grid.availability != Availability.ACTIVE) return;
        order.backoffUntil = 0;
        order.cooldownUntil = 0;
        coordinator.requestStockRead(gridKey);
    }

    /**
     * Follows the network's availability, its switches and its stock lease; queues the orders when anything new
     * arrived.
     */
    private void syncGrid(GridState grid) {
        IAEGrid live = identities.getGrid(grid.key);
        GridPersistentData data = identities.getPersistentData(grid.key);
        GridSettingsData gridSettings = data == null ? null : data.getSettings();
        Availability availability;
        if (live == null || gridSettings == null || !isUsable(live)) availability = Availability.UNAVAILABLE;
        else if (!config.enabled || !gridSettings.isStandingOrdersEnabled()) availability = Availability.SWITCHED_OFF;
        else availability = Availability.ACTIVE;
        boolean tracked = gridSettings != null && gridSettings.isStorageTracked();
        if (gridSettings != null) grid.intervalMillis = SECONDS.toMillis(gridSettings.getStockSampleIntervalSeconds());
        boolean replaced = availability != Availability.UNAVAILABLE && live != grid.seen;
        if (availability != grid.availability || replaced) {
            // Whatever was calculated or read belongs to the previous network state.
            grid.fed = false;
            grid.availability = availability;
            grid.seen = availability == Availability.UNAVAILABLE ? null : live;
            grid.live = availability == Availability.ACTIVE ? live : null;
            grid.baseline = null;
            grid.stockFailed = false;
            for (OrderState order : grid.orders) {
                cancelCalculation(order);
                enqueue(order);
            }
        }
        boolean lease = availability == Availability.ACTIVE && grid.hasEnabledOrder && !tracked;
        if (lease != grid.leased) {
            grid.leased = lease;
            if (lease) coordinator.leaseStock(grid.key);
            else coordinator.releaseStock(grid.key);
        }
        // Stock is only trustworthy while something keeps reading it; without a feed it would age unnoticed.
        boolean fed = availability == Availability.ACTIVE && (tracked || lease);
        if (!fed) grid.baseline = null;
        else if (!grid.fed) coordinator.requestStockRead(grid.key);
        grid.fed = fed;
        if (grid.changed) {
            grid.changed = false;
            for (OrderState order : grid.orders) {
                if (order.status != StandingOrderStatus.CALCULATING && order.status != StandingOrderStatus.SUBMIT
                    && order.definition != null
                    && order.definition.enabled()) enqueue(order);
            }
        }
    }

    private static boolean isUsable(IAEGrid grid) {
        try {
            return GridFilter.isUsable(grid);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void pollCalculations(long now) {
        for (int index = calculating.size() - 1; index >= 0; index--) {
            OrderState order = calculating.get(index);
            Future<IAECraftingJob> future = order.calculation;
            if (future == null || !future.isDone()) continue;
            GridState grid = grids.get(order.grid);
            releaseCalculation(order, grid);
            try {
                IAECraftingJob job = future.get();
                if (job.web$isSimulation() && !order.simulationAllowed) {
                    fail(order, StandingOrderStatus.BLOCKED_MISSING, "The network lacks ingredients for the job", now);
                } else {
                    order.job = job;
                    setStatus(order, StandingOrderStatus.SUBMIT, null, now);
                    enqueueFirst(order);
                }
            } catch (ExecutionException | RuntimeException failure) {
                LOG.warn("Calculation of standing order {} failed", order.key, failure);
                fail(order, StandingOrderStatus.SUBMIT_FAILED, "Calculation failed", now);
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                fail(order, StandingOrderStatus.SUBMIT_FAILED, "Calculation was interrupted", now);
            }
            publish(order, grid);
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
        OrderState order;
        while ((order = work.poll()) != null) {
            evaluate(order, now);
            if (nanoClock.getAsLong() >= deadline) break;
        }
    }

    // ----- Evaluation (server thread) -----

    private void evaluate(OrderState order, long now) {
        order.queued = false;
        if (order.removed) return;
        GridState grid = grids.get(order.grid);
        try {
            evaluateChecked(order, grid, now);
        } catch (RuntimeException failure) {
            LOG.warn("Standing order {} failed unexpectedly", order.key, failure);
            cancelCalculation(order);
            fail(order, StandingOrderStatus.SUBMIT_FAILED, "Unexpected error", now);
        }
        publish(order, grid);
    }

    private void evaluateChecked(OrderState order, GridState grid, long now) {
        StandingOrderDefinition definition = order.definition;
        if (definition == null) {
            String reason = order.invalid == null ? "" : order.invalid.getMessage();
            setStatus(order, StandingOrderStatus.UNAVAILABLE, "The stored definition is invalid: " + reason, now);
            return;
        }
        if (!definition.enabled()) {
            cancelCalculation(order);
            setStatus(order, StandingOrderStatus.DISABLED, null, now);
            return;
        }
        IAEGrid live = grid.live;
        if (live == null) {
            cancelCalculation(order);
            setStatus(
                order,
                grid.availability == Availability.SWITCHED_OFF ? StandingOrderStatus.SWITCHED_OFF
                    : StandingOrderStatus.GRID_UNAVAILABLE,
                null,
                now);
            return;
        }
        switch (order.status) {
            case CALCULATING -> {
            }
            case SUBMIT -> {
                IAECraftingJob plan = order.job;
                order.job = null;
                start(order, grid, live, definition, plan, now);
            }
            case CRAFTING, CRAFTING_LONG -> follow(order, grid, live, definition, now);
            default -> start(order, grid, live, definition, null, now);
        }
    }

    /** A running job: report how long it has been going and notice when it left its CPU. */
    private void follow(OrderState order, GridState grid, IAEGrid live, StandingOrderDefinition definition, long now) {
        if (cpuView(grid, live).finalOutputs.contains(
            definition.action()
                .resource())) {
            enterCrafting(order, now);
            return;
        }
        long cooldown = SECONDS.toMillis(definition.cooldownSeconds());
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
        // Gated on the time, not the status: an outage or a disable in between must not end the cooldown early.
        if (now < order.cooldownUntil) {
            setStatus(order, StandingOrderStatus.COOLDOWN, null, now);
            schedule(order, order.cooldownUntil);
            return;
        }
        if (order.status == StandingOrderStatus.COOLDOWN) setStatus(order, StandingOrderStatus.IDLE, null, now);
        StockBaseline baseline = grid.baseline;
        if (baseline == null) {
            setStatus(
                order,
                StandingOrderStatus.WAITING_FOR_STOCK,
                grid.stockFailed ? "The last stock read failed" : "Waiting for the first stock read of the network",
                now);
            return;
        }
        // A job that just left its CPU may still be missing from older reads, and would be ordered again.
        if (baseline.observedAt < order.settledAt) {
            setStatus(
                order,
                StandingOrderStatus.WAITING_FOR_STOCK,
                "Waiting for a stock read taken after the last job finished",
                now);
            return;
        }
        CraftAction action = definition.action();
        try {
            StandingOrderValidator.validateCapabilities(definition, capabilities());
        } catch (InvalidStandingOrderException unsupported) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, "The server does not support " + unsupported.path(), now);
            return;
        }
        IAEKey resource = resolve(action.resource());
        if (resource == null) {
            setStatus(
                order,
                StandingOrderStatus.UNAVAILABLE,
                "The resource is not known on this network, or is ambiguous",
                now);
            return;
        }
        for (StableKey triggerResource : order.triggerResources) {
            if (isAmbiguous(triggerResource)) {
                setStatus(
                    order,
                    StandingOrderStatus.UNAVAILABLE,
                    "A resource of the trigger is ambiguous on this network, so its stock cannot be read",
                    now);
                return;
            }
        }
        Truth trigger = definition.trigger()
            .evaluate(new Input(grid, live, baseline));
        if (trigger == Truth.UNKNOWN) {
            setStatus(
                order,
                StandingOrderStatus.WAITING_FOR_STOCK,
                "Waiting for a stock read that includes the resources of this order",
                now);
            return;
        }
        if (trigger == Truth.FALSE) {
            order.failures = 0;
            order.backoffUntil = 0;
            setStatus(order, StandingOrderStatus.IDLE, null, now);
            return;
        }
        if (now < order.backoffUntil) {
            setStatus(order, order.failureStatus, order.failureMessage, now);
            schedule(order, order.backoffUntil);
            return;
        }
        IAECraftingGrid crafting = live.web$getCraftingGrid();
        // Order matters on legacy adapters: the crafting check rebuilds stale pattern state first.
        if (!crafting.web$isCurrentlyCraftable(resource)) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, "The resource is not craftable", now);
            return;
        }
        if (crafting.web$canEmitFor(resource)) {
            setStatus(
                order,
                StandingOrderStatus.UNAVAILABLE,
                "The resource is provided by a level emitter and cannot be ordered",
                now);
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
                selected == null ? "No idle crafting CPU accepts standing orders"
                    : "The selected crafting CPU is not idle or does not accept standing orders",
                now);
            return;
        }
        OptionalLong stored = baseline.stored(action.resource());
        if (!stored.isPresent()) {
            setStatus(
                order,
                StandingOrderStatus.WAITING_FOR_STOCK,
                "Waiting for a stock read that includes the resources of this order",
                now);
            return;
        }
        OrderAmount amount = action.amount()
            .orderAmount(stored.getAsLong());
        if (amount.outcome() == OrderAmount.Outcome.NOTHING_TO_ORDER) {
            setStatus(order, StandingOrderStatus.IDLE, "Nothing to order: the stock already reaches the target", now);
            return;
        }
        if (amount.outcome() == OrderAmount.Outcome.OVERFLOW) {
            setStatus(order, StandingOrderStatus.UNAVAILABLE, "The amount to order cannot be computed", now);
            return;
        }
        if (plan != null) {
            // Orders only ever plan up to the batch limit, so a still positive need cannot be overshot by more.
            submit(order, definition, live, crafting, plan, target, now);
        } else {
            beginCalculation(order, grid, definition, live, crafting, resource, amount.amount(), now);
        }
    }

    private void beginCalculation(OrderState order, GridState grid, StandingOrderDefinition definition, IAEGrid live,
        IAECraftingGrid crafting, IAEKey resource, long amount, long now) {
        if (grid.inFlight >= config.maxCalculationsInFlightPerGrid) {
            setStatus(
                order,
                StandingOrderStatus.QUEUED,
                "The network already runs the allowed number of calculations",
                now);
            return;
        }
        order.calculation = crafting.web$beginCraftingJob(
            live,
            resource,
            amount,
            definition.action()
                .options());
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
            error = "Submitting the job failed";
        }
        if (error != null) {
            fail(order, StandingOrderStatus.SUBMIT_FAILED, error, now);
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
        if (order.status != StandingOrderStatus.CRAFTING && order.status != StandingOrderStatus.CRAFTING_LONG) {
            order.craftingSince = now;
        }
        if (now - order.craftingSince >= threshold) {
            setStatus(order, StandingOrderStatus.CRAFTING_LONG, null, now);
        } else {
            setStatus(order, StandingOrderStatus.CRAFTING, null, now);
            schedule(order, order.craftingSince + threshold);
        }
    }

    /** Records a failure and keeps the order from starting again until its exponentially growing backoff passed. */
    private void fail(OrderState order, StandingOrderStatus status, String message, long now) {
        order.failures++;
        long delay = BACKOFF_FIRST_MILLIS << Math.min(order.failures - 1, 16);
        order.backoffUntil = now + Math.min(delay, BACKOFF_MAX_MILLIS);
        order.failureStatus = status;
        order.failureMessage = message;
        setStatus(order, status, message, now);
        schedule(order, order.backoffUntil);
    }

    private void setStatus(OrderState order, StandingOrderStatus status, @Nullable String message, long now) {
        if (order.status == status && Objects.equals(order.message, message)) return;
        order.status = status;
        order.message = message;
        order.changedAt = now;
    }

    private void publish(OrderState order, @Nullable GridState grid) {
        if (order.removed) return;
        StandingOrderSnapshot next = new StandingOrderSnapshot(
            order.status,
            order.message,
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
            case CALCULATING, SUBMIT, DISABLED, SWITCHED_OFF, GRID_UNAVAILABLE -> 0;
            default -> grid == null || grid.baseline == null ? 0 : grid.baseline.observedAt + grid.intervalMillis;
        };
    }

    // ----- Queues -----

    private void enqueue(OrderState order) {
        if (order.queued || order.removed) return;
        order.queued = true;
        work.addLast(order);
    }

    private void enqueueFirst(OrderState order) {
        if (order.queued) work.remove(order);
        order.queued = true;
        work.addFirst(order);
    }

    private void schedule(OrderState order, long at) {
        if (order.wakeAt == at) return;
        order.wakeAt = at;
        timers.add(new Timer(at, order));
    }

    // ----- Calculations -----

    /** Drops a running calculation or a finished plan that was not submitted yet. */
    private void cancelCalculation(OrderState order) {
        Future<IAECraftingJob> future = order.calculation;
        if (future != null) {
            if (!future.isDone()) future.cancel(true);
            releaseCalculation(order, grids.get(order.grid));
        }
        order.job = null;
        if (order.status == StandingOrderStatus.CALCULATING || order.status == StandingOrderStatus.SUBMIT) {
            order.status = StandingOrderStatus.IDLE;
            order.message = null;
        }
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

    private static @Nullable IAEKey resolve(StableKey resource) {
        try {
            return AE2Controller.itemIdentities.resolve(resource);
        } catch (ItemIdentityRegistry.Ambiguous ambiguous) {
            return null;
        }
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
        /** The usable native network last seen, to notice that it was replaced. */
        private @Nullable IAEGrid seen;
        /** The native network orders are evaluated against; present exactly while the grid is ACTIVE. */
        private @Nullable IAEGrid live;
        private @Nullable StockBaseline baseline;
        private boolean stockFailed;
        private long intervalMillis;
        private boolean hasEnabledOrder;
        private boolean leased;
        private boolean fed;
        private boolean changed;
        private int inFlight;

        private GridState(StableKey key) {
            this.key = key;
        }

        /** Recomputes what the orders need after the set of orders or a definition changed. */
        private void refresh() {
            referenced.clear();
            hasEnabledOrder = false;
            for (OrderState order : orders) {
                StandingOrderDefinition definition = order.definition;
                if (definition == null) continue;
                hasEnabledOrder |= definition.enabled();
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
        private @Nullable InvalidStandingOrderException invalid;
        private final Set<StableKey> triggerResources = new HashSet<>();
        private StandingOrderStatus status = StandingOrderStatus.IDLE;
        private @Nullable String message;
        private long changedAt;
        private long craftingSince;
        private long lastSubmittedAmount;
        private long settledAt;
        private long cooldownUntil;
        private long backoffUntil;
        private int failures;
        private StandingOrderStatus failureStatus = StandingOrderStatus.SUBMIT_FAILED;
        private @Nullable String failureMessage;
        private long wakeAt;
        private @Nullable Future<IAECraftingJob> calculation;
        private @Nullable IAECraftingJob job;
        private long plannedAmount;
        private boolean simulationAllowed;
        private boolean queued;
        private boolean removed;

        private OrderState(StableKey key, StableKey grid) {
            this.key = key;
            this.grid = grid;
        }

        /** The crafted resource changed, so a running job, cooldown and failures no longer belong to this order. */
        private void forgetJob() {
            craftingSince = 0;
            settledAt = 0;
            cooldownUntil = 0;
            backoffUntil = 0;
            failures = 0;
            if (status == StandingOrderStatus.CRAFTING || status == StandingOrderStatus.CRAFTING_LONG
                || status == StandingOrderStatus.COOLDOWN) {
                status = StandingOrderStatus.IDLE;
                message = null;
            }
        }
    }
}
