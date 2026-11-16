package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.CoreEngine.GRID_IDENTITIES;
import static pl.kuba6000.ae2webintegration.core.api.ServerCapability.CRAFTING_IGNORE_MISSING;
import static pl.kuba6000.ae2webintegration.core.api.ServerCapability.CRAFTING_LIGHT_MODE;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStatus.*;
import static pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingTestFixtures.awaitHistory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveHub;
import pl.kuba6000.ae2webintegration.core.standingorders.AmountPolicy;
import pl.kuba6000.ae2webintegration.core.standingorders.Condition;
import pl.kuba6000.ae2webintegration.core.standingorders.CpuTarget;
import pl.kuba6000.ae2webintegration.core.standingorders.CraftAction;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderDefinition;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEngine;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderReason;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderSnapshot;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStatus;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.CraftingRequesterContext;
import pl.kuba6000.ae2webintegration.core.tracking.StorageTrackingCoordinator;
import pl.kuba6000.ae2webintegration.core.tracking.storage.InMemoryStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

/**
 * The engine through its public behavior: a real tracking coordinator reads a fake network, the test advances the
 * clock tick by tick and observes statuses, calculations and submissions.
 */
@SuppressWarnings("PMD.AvoidMagicNumbers") // Quantities, times and seeds are arbitrary fixture values.
class StandingOrderEngineTest extends GridTestScope {

    private static final long MINUTE = 60_000;
    private static final long SWEEP = StandingOrderEngine.SWEEP_MILLIS;
    private static final CraftingOptions PLAIN = new CraftingOptions(false, false);
    private static final CraftAction FILL_PLASTIC = new CraftAction(
        key("so-plastic"),
        new AmountPolicy.FillTo(1000, 500),
        PLAIN,
        CpuTarget.any());

    private final ConfigSettings.StandingOrders settings = new ConfigSettings.StandingOrders();
    private final Map<ServerCapability, Boolean> capabilities = new EnumMap<>(ServerCapability.class);
    private LongSupplier clock = () -> 0;
    private long now = 10_000_000;
    private boolean settingsFail;
    private int ordinal;
    private OrderGrid grid;
    private TestGridFixtures.TestAE ae;
    private StableKey gridKey;
    private StorageTrackingService service;
    private StorageTrackingCoordinator coordinator;
    private StandingOrderEngine engine;

    @BeforeEach
    void createEngine() {
        grid = new OrderGrid();
        gridKey = TestGridFixtures.resolvedKey(grid);
        ae = TestGridFixtures.ae(grid);
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStockSampleIntervalSeconds(60);
        service = new StorageTrackingService(new InMemoryStorageHistoryStore(), 100);
        coordinator = new StorageTrackingCoordinator(
            service,
            GRID_IDENTITIES,
            new ConfigSettings.ResourceTracking(),
            new TerminalRegistry(new LiveHub()));
        engine = newEngine();
    }

    @AfterEach
    void closeEngine() {
        engine.close();
        coordinator.close();
        service.close();
        AE2Controller.itemIdentities.clear();
    }

    private StandingOrderEngine newEngine() {
        return new StandingOrderEngine(coordinator, GRID_IDENTITIES, this::currentSettings, () -> capabilities, clock);
    }

    private ConfigSettings.StandingOrders currentSettings() {
        if (settingsFail) throw new IllegalStateException("settings unavailable");
        return settings;
    }

    // ----- Trigger, calculation, submission, crafting, cooldown -----

    @Test
    void aLowStockTriggerCalculatesSubmitsAndRunsThroughCooldown() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 120);
        tick(0);
        assertEquals(WAITING_FOR_STOCK, status(order));
        tick(1000);
        assertEquals(CALCULATING, status(order));
        assertEquals(1, grid.calculations.size());
        OrderGrid.Calculation calculation = grid.calculations.get(0);
        assertEquals(64, calculation.amount);
        assertEquals(key("so-plastic"), calculation.resource.web$getKey());
        assertEquals(PLAIN, calculation.options);
        assertSame(grid, calculation.grid);

        calculation.future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(order));
        OrderGrid.Submission submission = grid.submissions.get(0);
        assertSame(calculation.future.getNow(null), submission.job);
        assertNull(submission.target);
        assertTrue(submission.prioritizePower);
        assertEquals(CraftingRequester.Source.STANDING_ORDER, submission.requester.source());
        assertEquals(order, submission.requester.orderKey());
        assertEquals("Keep plastic", submission.requester.orderName());
        StandingOrderSnapshot crafting = snapshot(order);
        assertEquals(64, crafting.lastSubmittedAmount());
        assertEquals(now, crafting.craftingSince());

        tick(MINUTE);
        assertEquals(CRAFTING, status(order));
        assertEquals(1, grid.calculations.size(), "A running job must not be ordered again");

        cpu.finish();
        tick(MINUTE);
        long finished = now;
        assertEquals(COOLDOWN, status(order));
        assertEquals(finished + 120_000, snapshot(order).nextCheckAt());
        assertEquals(0, snapshot(order).craftingSince());
        tick(MINUTE);
        assertEquals(COOLDOWN, status(order));
        assertEquals(1, grid.calculations.size());
        tick(MINUTE);
        assertEquals(CALCULATING, status(order));
        assertEquals(2, grid.calculations.size());
    }

    @Test
    void aTriggerThatDoesNotHoldKeepsTheOrderIdleAndTheNetworkUntouchedBetweenReads() {
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(IDLE, status(order));
        assertEquals(now + MINUTE, snapshot(order).nextCheckAt());
        assertEquals(0, grid.emitterQueries, "Crafting state is only queried for orders whose trigger holds");
        int cpuReads = grid.cpuReads;
        int storageReads = grid.storageReads;
        for (int index = 0; index < 5; index++) tick(1000);
        assertEquals(cpuReads, grid.cpuReads);
        assertEquals(storageReads, grid.storageReads);
        assertTrue(grid.calculations.isEmpty());
    }

    @Test
    void anUntrackedGridDoesNotNeedAnyReadWhileNoOrderExists() {
        tick(0);
        tick(1000);
        tick(MINUTE);
        assertEquals(0, grid.storageReads);
        assertNull(grid.web$getStorageCapture());
    }

    // ----- Already crafting, CPU eligibility -----

    @Test
    void aBusyCpuWhoseFinalOutputIsTheResourceBlocksTheOrderAcrossARestart() {
        OrderGrid.Cpu cpu = grid.addCpu();
        cpu.craft("so-plastic");
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(CRAFTING, status(order));
        assertTrue(grid.calculations.isEmpty());

        engine.close();
        engine = newEngine();
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 0);
        tick(0);
        tick(1000);
        assertEquals(CRAFTING, status(order));
        assertTrue(grid.calculations.isEmpty(), "A job that survived the restart must not be ordered twice");
        assertTrue(grid.submissions.isEmpty());
    }

    @Test
    void anIntermediateOfAnotherJobDoesNotBlockTheOrder() {
        OrderGrid.Cpu busy = grid.addCpu();
        busy.craft("so-circuit");
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(CALCULATING, status(order));
    }

    @Test
    void cpusThatOnlyServeAutomationNeitherCountNorRunTheOrder() {
        OrderGrid.Cpu automation = grid.addCpu();
        automation.acceptsPlayerJobs = false;
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey waiting = post("Wait", below("so-plastic", 1000), fixed("so-plastic", 64));
        StableKey counted = post(
            "Counted",
            new Condition.All(Arrays.asList(below("so-plastic", 1000), new Condition.IdleCpusAtLeast(1))),
            fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(WAITING_FOR_CPU, status(waiting));
        assertEquals(StandingOrderReason.NO_IDLE_CPU, snapshot(waiting).reason());
        assertEquals(IDLE, status(counted));
        assertTrue(grid.calculations.isEmpty());

        automation.acceptsPlayerJobs = true;
        settings.maxCalculationsInFlightPerGrid = 2;
        tick(MINUTE);
        assertEquals(CALCULATING, status(waiting));
        assertEquals(CALCULATING, status(counted));
    }

    @Test
    void aSelectedCpuMustBeIdleAndAcceptPlayerJobs() {
        OrderGrid.Cpu other = grid.addCpu();
        OrderGrid.Cpu selected = grid.addCpu();
        selected.craft("so-circuit");
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        CraftAction action = new CraftAction(
            key("so-plastic"),
            new AmountPolicy.Fixed(64),
            PLAIN,
            CpuTarget.specific(selected.key));
        StableKey order = post("Pinned", below("so-plastic", 1000), action, true, 0);
        tick(0);
        tick(1000);
        assertEquals(WAITING_FOR_CPU, status(order));
        assertEquals(StandingOrderReason.SELECTED_CPU_UNAVAILABLE, snapshot(order).reason());
        assertNotNull(other);
        selected.finish();
        tick(MINUTE);
        assertEquals(CALCULATING, status(order));
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertSame(selected, grid.submissions.get(0).target);
    }

    // ----- Failures and backoff -----

    @Test
    void missingIngredientsBlockTheOrderWithGrowingBackoffCappedAtTenMinutes() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        long[] delaysSeconds = { 30, 60, 120, 240, 480, 600, 600 };
        for (int attempt = 0; attempt < delaysSeconds.length; attempt++) {
            assertEquals(attempt + 1, grid.calculations.size());
            grid.calculations.get(attempt).future.complete(new FakeJob(true));
            tick(0);
            assertEquals(BLOCKED_MISSING, status(order));
            assertEquals(StandingOrderReason.MISSING_INGREDIENTS, snapshot(order).reason());
            long delay = delaysSeconds[attempt] * 1000;
            assertEquals(now + delay, snapshot(order).nextCheckAt());
            tick(delay - 1);
            assertEquals(attempt + 1, grid.calculations.size(), "Nothing may start during the backoff");
            assertEquals(BLOCKED_MISSING, status(order));
            tick(1);
        }
        assertTrue(grid.submissions.isEmpty());
        assertEquals(delaysSeconds.length + 1, grid.calculations.size());
    }

    @Test
    void aBlockedOrderNamesTheFiveLargestShortfallsOfItsCalculation() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        FakeJob job = new FakeJob(
            true,
            shortfall("so-a", 3),
            shortfall("so-b", 40),
            shortfall("so-c", 0),
            shortfall("so-d", 12),
            shortfall("so-e", 7),
            shortfall("so-f", 99),
            shortfall("so-g", 1),
            shortfall("so-h", 20));
        int storage = grid.storageReads;
        grid.calculations.get(0).future.complete(job);
        tick(0);
        assertEquals(BLOCKED_MISSING, status(order));
        assertEquals(StandingOrderReason.MISSING_INGREDIENTS, snapshot(order).reason());
        assertEquals(1, job.summaries);
        assertEquals(storage, grid.storageReads, "The shortfalls come from the job, not from another stock read");
        List<StandingOrderSnapshot.MissingIngredient> missing = snapshot(order).missing();
        assertEquals(5, missing.size());
        long[] amounts = { 99, 40, 20, 12, 7 };
        String[] names = { "so-f", "so-b", "so-h", "so-d", "so-e" };
        for (int index = 0; index < 5; index++) {
            assertEquals(
                amounts[index],
                missing.get(index)
                    .amount());
            assertEquals(
                names[index],
                missing.get(index)
                    .displayName());
            assertEquals(
                key(names[index]),
                missing.get(index)
                    .resource());
        }
        tick(30_000);
        grid.calculations.get(1).future.complete(new FakeJob(false));
        tick(0);
        assertEquals(CRAFTING, status(order));
        assertTrue(
            snapshot(order).missing()
                .isEmpty(),
            "Only the blocked status carries shortfalls");
    }

    @Test
    void aSummaryThatCannotBeReadStillBlocksTheOrderWithoutShortfalls() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        FakeJob job = new FakeJob(true, shortfall("so-a", 3));
        job.summaryFails = true;
        grid.calculations.get(0).future.complete(job);
        tick(0);
        assertEquals(BLOCKED_MISSING, status(order));
        assertTrue(
            snapshot(order).missing()
                .isEmpty());
    }

    private static ICraftingPlanSummaryEntry shortfall(String id, long missing) {
        ItemIdentityRequestTest.Resource what = new ItemIdentityRequestTest.Resource(id, 0, false);
        return new ICraftingPlanSummaryEntry() {

            @Override
            public IAEKey web$getWhat() {
                return what;
            }

            @Override
            public long web$getMissingAmount() {
                return missing;
            }

            @Override
            public long web$getStoredAmount() {
                return 0;
            }

            @Override
            public long web$getCraftAmount() {
                return 0;
            }
        };
    }

    @Test
    void ignoringMissingIngredientsSubmitsASimulatedPlan() {
        capabilities.put(CRAFTING_IGNORE_MISSING, true);
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        CraftAction action = new CraftAction(
            key("so-plastic"),
            new AmountPolicy.Fixed(64),
            new CraftingOptions(false, true),
            CpuTarget.any());
        StableKey order = post("Keep plastic", below("so-plastic", 1000), action, true, 0);
        tick(0);
        tick(1000);
        assertEquals(new CraftingOptions(false, true), grid.calculations.get(0).options);
        FakeJob simulated = new FakeJob(true);
        grid.calculations.get(0).future.complete(simulated);
        tick(1000);
        assertEquals(CRAFTING, status(order));
        assertEquals(1, grid.submissions.size());
        assertEquals(0, simulated.summaries, "A plan that is submitted anyway is not summarized");
    }

    @Test
    void aSubmissionErrorKeepsItsMessageAndRetriesAfterBackoff() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.submitError = "No suitable CPU found for the crafting job.";
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(SUBMIT_FAILED, status(order));
        assertEquals(StandingOrderReason.SUBMIT_REJECTED, snapshot(order).reason());
        assertEquals("No suitable CPU found for the crafting job.", snapshot(order).submitError());
        assertEquals(now + 30_000, snapshot(order).nextCheckAt());
        assertEquals(0, snapshot(order).lastSubmittedAmount());

        grid.submitError = null;
        tick(30_000);
        assertEquals(CALCULATING, status(order));
        assertEquals(2, grid.calculations.size());
        grid.calculations.get(1).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(order));
        assertNull(snapshot(order).reason());
        assertNull(snapshot(order).submitError());
    }

    @Test
    void aFailedCalculationIsReportedAndRetriedAfterBackoff() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.completeExceptionally(new IllegalStateException("No patterns"));
        tick(0);
        assertEquals(SUBMIT_FAILED, status(order));
        assertEquals(StandingOrderReason.CALCULATION_FAILED, snapshot(order).reason());
        assertNull(snapshot(order).submitError(), "Only the crafting system's own message is free text");
        tick(30_000);
        assertEquals(2, grid.calculations.size());
    }

    // ----- Re-check before submit -----

    @Test
    void aFinishedPlanIsDroppedWhenTheTriggerNoLongerHoldsOnTheLatestRead() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.stock("so-plastic", 5000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(MINUTE);
        assertEquals(IDLE, status(order));
        assertTrue(grid.submissions.isEmpty());
    }

    @Test
    void aRunningCalculationIsCancelledWhenANewerReadShowsThatTheTriggerNoLongerHolds() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        OrderGrid.Calculation calculation = grid.calculations.get(0);
        assertEquals(CALCULATING, status(order));

        grid.stock("so-plastic", 900);
        tick(MINUTE);
        assertEquals(CALCULATING, status(order), "A trigger that still holds keeps the calculation");
        assertFalse(calculation.future.isCancelled());

        grid.stock("so-plastic", 5000);
        tick(MINUTE);
        assertTrue(calculation.future.isCancelled());
        assertEquals(IDLE, status(order));
        assertEquals(now, snapshot(order).changedAt());
        assertEquals(1, grid.calculations.size());
        assertTrue(grid.submissions.isEmpty());

        grid.stock("so-plastic", 100);
        tick(MINUTE);
        assertEquals(CALCULATING, status(order), "The freed slot starts the order again when its trigger holds");
        assertEquals(2, grid.calculations.size());
    }

    @Test
    void aCalculationThatRunsLongerThanTheLimitIsCancelledAndTheOrderBacksOff() {
        settings.maxCalculationMinutes = 2;
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        OrderGrid.Calculation calculation = grid.calculations.get(0);
        tick(2 * MINUTE - 1);
        assertEquals(CALCULATING, status(order));
        assertFalse(calculation.future.isCancelled());
        tick(1);
        assertTrue(calculation.future.isCancelled());
        assertEquals(SUBMIT_FAILED, status(order));
        assertEquals(StandingOrderReason.CALCULATION_TIMED_OUT, snapshot(order).reason());
        assertEquals(now + 30_000, snapshot(order).nextCheckAt());
        tick(30_000 - 1);
        assertEquals(1, grid.calculations.size(), "The backoff holds");
        tick(1);
        assertEquals(CALCULATING, status(order));
        assertEquals(2, grid.calculations.size(), "The slot of the cancelled calculation is free again");
    }

    @Test
    void aPlanIsDroppedWhenAnotherJobStartedCraftingTheResource() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        cpu.craft("so-plastic");
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(order));
        assertTrue(grid.submissions.isEmpty());
    }

    @Test
    void aPlanIsDroppedWhenNoEligibleCpuIsLeft() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        cpu.craft("so-circuit");
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(WAITING_FOR_CPU, status(order));
        assertTrue(grid.submissions.isEmpty());
    }

    @Test
    void aFillToPlanIsSubmittedWhileTheNeedIsPositiveEvenIfItShrank() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Fill plastic", below("so-plastic", 2000), FILL_PLASTIC);
        tick(0);
        tick(1000);
        assertEquals(500, grid.calculations.get(0).amount, "The shortfall is capped by the batch limit");
        grid.stock("so-plastic", 700);
        tick(MINUTE);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(order));
        assertEquals(500, snapshot(order).lastSubmittedAmount());
    }

    @Test
    void aFillToPlanIsDroppedOnceTheTargetIsReached() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Fill plastic", below("so-plastic", 2000), FILL_PLASTIC);
        tick(0);
        tick(1000);
        grid.stock("so-plastic", 1200);
        tick(MINUTE);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(IDLE, status(order));
        assertEquals(StandingOrderReason.NOTHING_TO_ORDER, snapshot(order).reason());
        assertTrue(grid.submissions.isEmpty());
    }

    @Test
    void aFillToAmountThatCannotBeComputedIsAnExplicitStatusNotAWrappedNumber() {
        grid.addCpu();
        grid.stock("so-plastic", Long.MIN_VALUE);
        grid.craftable("so-plastic");
        StableKey order = post("Fill plastic", below("so-plastic", 2000), FILL_PLASTIC);
        tick(0);
        tick(1000);
        assertEquals(UNAVAILABLE, status(order));
        assertEquals(StandingOrderReason.AMOUNT_OVERFLOW, snapshot(order).reason());
        assertTrue(grid.calculations.isEmpty());
    }

    // ----- Stock data -----

    @Test
    void aNewOrderWaitsForTheFirstReadAndAnOrderWithAnUnreadResourceAsksForOne() {
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        grid.stock("so-extra", 50);
        StableKey first = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        assertEquals(WAITING_FOR_STOCK, status(first));
        assertEquals(StandingOrderReason.WAITING_FIRST_READ, snapshot(first).reason());
        tick(1000);
        assertEquals(IDLE, status(first));
        int reads = grid.storageReads;

        StableKey second = post("Extra", below("so-extra", 100), fixed("so-plastic", 64));
        tick(0);
        assertEquals(WAITING_FOR_STOCK, status(second));
        assertEquals(StandingOrderReason.WAITING_READ_FOR_NEW_RESOURCES, snapshot(second).reason());
        tick(1000);
        assertEquals(reads + 1, grid.storageReads, "A resource missing from the baseline costs one extra read");
        assertEquals(CALCULATING, status(second));
        tick(MINUTE);
        assertEquals(reads + 2, grid.storageReads);
    }

    @Test
    void aFailedStockReadDropsTheBaselineUntilTheNextCompleteRead() {
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(IDLE, status(order));
        grid.failReads = true;
        tick(MINUTE);
        assertEquals(WAITING_FOR_STOCK, status(order));
        assertEquals(StandingOrderReason.READ_FAILED, snapshot(order).reason());
        grid.failReads = false;
        grid.stock("so-plastic", 100);
        tick(2 * MINUTE);
        assertEquals(CALCULATING, status(order));
    }

    @Test
    void aNewOrderMayOnlyStartFromAReadTakenAfterThePreviousJobLeftItsCpu() {
        settings.longRunningJobMinutes = 1;
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(order));
        tick(MINUTE - 1000);
        assertEquals(CRAFTING, status(order));
        cpu.finish();
        tick(1000);
        assertEquals(WAITING_FOR_STOCK, status(order), "The held read predates the end of the job");
        assertEquals(StandingOrderReason.WAITING_FRESH_READ_AFTER_JOB, snapshot(order).reason());
        assertEquals(1, grid.calculations.size());
        tick(MINUTE);
        assertEquals(CALCULATING, status(order));
        assertEquals(2, grid.calculations.size());
    }

    @Test
    void aLongRunningJobIsReportedAndNeverCancelled() {
        settings.longRunningJobMinutes = 1;
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(order));
        tick(MINUTE);
        assertEquals(CRAFTING_LONG, status(order));
        tick(MINUTE);
        assertEquals(CRAFTING_LONG, status(order));
        assertFalse(cpu.cancelled);
        assertEquals(1, grid.calculations.size());
        cpu.finish();
        tick(MINUTE);
        assertNotEquals(CRAFTING_LONG, status(order));
    }

    // ----- Availability of the resource and of the server -----

    @Test
    void anUnknownResourceIsUnavailableUntilTheNetworkReportsIt() {
        grid.addCpu();
        StableKey order = post("Ghost", below("so-ghost", 10), fixed("so-ghost", 64));
        tick(0);
        tick(1000);
        assertEquals(UNAVAILABLE, status(order));
        assertEquals(StandingOrderReason.UNKNOWN_RESOURCE, snapshot(order).reason());
        grid.craftable("so-ghost");
        tick(MINUTE);
        assertEquals(CALCULATING, status(order));
    }

    @Test
    void aResourceThatIsNotCraftableAnymoreIsUnavailable() {
        grid.addCpu();
        grid.stock("so-iron", 5);
        StableKey order = post("Iron", below("so-iron", 10), fixed("so-iron", 64));
        tick(0);
        tick(1000);
        assertEquals(UNAVAILABLE, status(order));
        assertEquals(StandingOrderReason.NOT_CRAFTABLE, snapshot(order).reason());
        assertTrue(grid.calculations.isEmpty());
    }

    @Test
    void anEmitterBackedResourceIsUnavailable() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.emitters.add(new ItemIdentityRequestTest.Resource("so-plastic", 0, false));
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(UNAVAILABLE, status(order));
        assertEquals(StandingOrderReason.EMITTER_BACKED, snapshot(order).reason());
        assertTrue(grid.calculations.isEmpty());
    }

    @Test
    void anOptionTheServerNoLongerSupportsMakesTheOrderUnavailable() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        CraftAction action = new CraftAction(
            key("so-plastic"),
            new AmountPolicy.Fixed(64),
            new CraftingOptions(true, false),
            CpuTarget.any());
        StableKey order = post("Lite", below("so-plastic", 1000), action, true, 0);
        tick(0);
        tick(1000);
        assertEquals(UNAVAILABLE, status(order));
        assertEquals(StandingOrderReason.CAPABILITY_UNAVAILABLE, snapshot(order).reason());
        assertEquals("action.options.lightMode", snapshot(order).path());
        capabilities.put(CRAFTING_LIGHT_MODE, true);
        tick(MINUTE);
        assertEquals(CALCULATING, status(order));
        assertEquals(new CraftingOptions(true, false), grid.calculations.get(0).options);
    }

    @Test
    void aStoredDefinitionThatCannotBeDecodedIsUnavailableAndNeverReadsTheNetwork() {
        StableKey order = StableKey.random();
        engine.post(
            gridKey,
            new StandingOrderStore.Invalid(
                order,
                0,
                "admin",
                new InvalidStandingOrderException(InvalidStandingOrderException.Reason.UNSUPPORTED_VERSION, "")));
        tick(0);
        tick(MINUTE);
        assertEquals(UNAVAILABLE, status(order));
        assertEquals(StandingOrderReason.INVALID_DEFINITION, snapshot(order).reason());
        assertEquals(0, grid.storageReads, "An undecodable order needs no stock");
    }

    // ----- Switches, cancellation, lifecycle -----

    @Test
    void disablingAnOrderDuringItsCalculationCancelsIt() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        OrderGrid.Calculation calculation = grid.calculations.get(0);
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), false, 0);
        tick(0);
        assertTrue(calculation.future.isCancelled());
        assertEquals(DISABLED, status(order));
        tick(MINUTE);
        assertEquals(DISABLED, status(order));
        assertEquals(1, grid.calculations.size());
        assertTrue(grid.submissions.isEmpty());
    }

    @Test
    void deletingAnOrderCancelsItsCalculationAndForgetsItsStatus() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        engine.postDeletion(gridKey, order);
        tick(0);
        assertTrue(grid.calculations.get(0).future.isCancelled());
        assertNull(engine.snapshot(order));
        tick(1000);
        assertNull(grid.web$getStorageCapture(), "The last order of a grid releases its stock lease");
    }

    @Test
    void replacingADefinitionCancelsTheObsoleteCalculationAndStartsTheNewOne() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 128), true, 0);
        tick(0);
        assertTrue(grid.calculations.get(0).future.isCancelled());
        assertEquals(2, grid.calculations.size());
        assertEquals(128, grid.calculations.get(1).amount);
        assertEquals(CALCULATING, status(order));
    }

    @Test
    void aBriefOutageOfTheNetworkOnlyBlocksAndKeepsEverythingTheOrderHolds() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        OrderGrid.Calculation calculation = grid.calculations.get(0);
        int reads = grid.storageReads;

        // Only the engine looks at the network here: the tracking coordinator's own reactions are not under test.
        grid.controllerState(AEControllerState.NO_CONTROLLER);
        engineTick(SWEEP);
        assertEquals(GRID_UNAVAILABLE, status(order));
        assertFalse(calculation.future.isCancelled(), "A running calculation survives a brief outage");
        calculation.future.complete(new FakeJob(false));
        engineTick(SWEEP);
        assertEquals(GRID_UNAVAILABLE, status(order));
        assertTrue(grid.submissions.isEmpty(), "Nothing is submitted while the network is away");

        grid.controllerState(AEControllerState.CONTROLLER_ONLINE);
        engineTick(SWEEP);
        assertEquals(CRAFTING, status(order), "The finished plan is checked and submitted once the network is back");
        assertEquals(1, grid.submissions.size());
        assertEquals(1, grid.calculations.size(), "The order was not calculated again");
        assertEquals(reads, grid.storageReads, "The engine asks for no read because the network was away briefly");
    }

    @Test
    void aBriefOutageKeepsTheStockSoOrdersNeitherWaitForNorRequestAnotherRead() {
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(IDLE, status(order));
        int reads = grid.storageReads;
        grid.stock("so-plastic", 100);

        grid.controllerState(AEControllerState.NO_CONTROLLER);
        engineTick(SWEEP);
        assertEquals(GRID_UNAVAILABLE, status(order));
        grid.controllerState(AEControllerState.CONTROLLER_ONLINE);
        engineTick(SWEEP);
        assertEquals(IDLE, status(order), "The stock read before the outage is still the baseline");
        assertEquals(reads, grid.storageReads);
        assertTrue(grid.calculations.isEmpty());
    }

    @Test
    void anOutageLongerThanTheStockIntervalDropsTheStaleStockAndAsksForARead() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        OrderGrid.Calculation calculation = grid.calculations.get(0);

        grid.controllerState(AEControllerState.NO_CONTROLLER);
        for (int sweeps = 0; sweeps < 20; sweeps++) engineTick(SWEEP);
        assertTrue(calculation.future.isCancelled(), "Stock that old belongs to a state that is gone");
        grid.controllerState(AEControllerState.CONTROLLER_ONLINE);
        engineTick(SWEEP);
        assertEquals(WAITING_FOR_STOCK, status(order));
        assertEquals(StandingOrderReason.WAITING_FIRST_READ, snapshot(order).reason());
        int reads = grid.storageReads;
        tick(1000);
        assertTrue(grid.storageReads > reads, "A fresh read was taken once the network was back");
        assertEquals(CALCULATING, status(order));
        assertEquals(2, grid.calculations.size());
    }

    @Test
    void anIdleTickReadsNothingFromTheNetworkAndOnlyTheSweepProbesIt() {
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        int probes = grid.pathingProbes;
        int storage = grid.storageReads;
        int cpus = grid.cpuReads;
        for (int index = 0; index < 3; index++) engineTick(1000);
        assertEquals(probes, grid.pathingProbes, "No availability probe between sweeps");
        assertEquals(storage, grid.storageReads);
        assertEquals(cpus, grid.cpuReads);
        engineTick(SWEEP);
        assertTrue(grid.pathingProbes > probes, "The sweep notices changes nothing else would");
        assertEquals(cpus, grid.cpuReads);
    }

    @Test
    void aSwitchChangeIsNoticedBySweepWithoutAnyOtherActivity() {
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStandingOrdersEnabled(false);
        engineTick(1000);
        assertEquals(IDLE, status(order), "Nothing looks at the network between sweeps");
        engineTick(SWEEP);
        assertEquals(SWITCHED_OFF, status(order));
    }

    @Test
    void shutdownCancelsEveryCalculationAndForgetsTheOrders() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        Future<?> calculation = grid.calculations.get(0).future;
        engine.close();
        assertTrue(calculation.isCancelled());
        assertNull(engine.snapshot(order));
    }

    @Test
    void theServerWideAndPerNetworkSwitchesStopOrdersAndReleaseTheLease() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        settings.enabled = false;
        tick(SWEEP);
        assertEquals(SWITCHED_OFF, status(order));
        assertTrue(grid.calculations.get(0).future.isCancelled());
        tick(1000);
        assertNull(grid.web$getStorageCapture());
        settings.enabled = true;
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStandingOrdersEnabled(false);
        tick(SWEEP);
        assertEquals(SWITCHED_OFF, status(order));
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStandingOrdersEnabled(true);
        tick(SWEEP);
        tick(1000);
        assertEquals(CALCULATING, status(order));
        assertEquals(2, grid.calculations.size());
    }

    // ----- Stock source -----

    @Test
    void anUntrackedGridIsLeasedOnlyWhileAnOrderIsEnabledAndWritesNoHistory() throws Exception {
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(1, grid.storageReads);
        tick(MINUTE);
        assertEquals(2, grid.storageReads);
        assertTrue(
            service.history(gridKey, null, 0, 100)
                .points()
                .isEmpty());

        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), false, 0);
        tick(0);
        tick(1000);
        assertNull(grid.web$getStorageCapture(), "No enabled order, no lease");
        tick(MINUTE);
        assertEquals(2, grid.storageReads);
    }

    @Test
    void aStockTrackedGridFeedsOrdersFromItsOwnSamplesWithoutALease() throws Exception {
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStorageTracked(true);
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(IDLE, status(order));
        int reads = grid.storageReads;
        for (int sample = 1; sample <= 3; sample++) {
            tick(MINUTE);
            assertEquals(reads + sample, grid.storageReads, "One read per tracker sample and nothing more");
            assertEquals(now + MINUTE, snapshot(order).nextCheckAt());
        }
        grid.stock("so-plastic", 100);
        tick(MINUTE);
        assertEquals(CALCULATING, status(order));
        awaitHistory(
            service,
            gridKey,
            0,
            stored -> !stored.points()
                .isEmpty());
    }

    @Test
    void switchingStockTrackingOffHandsTheFeedToALeaseWithoutLosingTheOrder() {
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStorageTracked(true);
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(IDLE, status(order));

        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStorageTracked(false);
        grid.stock("so-plastic", 100);
        tick(SWEEP);
        tick(1000);
        assertEquals(CALCULATING, status(order));
    }

    // ----- Run now, in-flight limit, budget -----

    @Test
    void runNowSkipsTheCooldownAndReadsOnceMoreButStillChecksTheTrigger() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 3600);
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        cpu.finish();
        tick(MINUTE);
        assertEquals(COOLDOWN, status(order));
        int reads = grid.storageReads;

        engine.requestRun(gridKey, order);
        tick(0);
        tick(1000);
        assertEquals(reads + 1, grid.storageReads);
        assertEquals(CALCULATING, status(order));
        assertEquals(2, grid.calculations.size());
    }

    @Test
    void repeatedRunRequestsAreCoalescedAndSpacedInsteadOfRefused() {
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStockSampleIntervalSeconds(900);
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        int reads = grid.storageReads;

        // The engine takes a request in its part of a tick, so the coordinator reads on the tick after.
        engine.requestRun(gridKey, order);
        engine.requestRun(gridKey, order);
        tick(0);
        tick(1000);
        assertEquals(reads + 1, grid.storageReads, "The first extra read is not delayed");
        engine.requestRun(gridKey, order);
        engine.requestRun(gridKey, order);
        tick(0);
        tick(1000);
        tick(26_000);
        assertEquals(reads + 1, grid.storageReads, "The next one waits for the minimum interval");
        assertEquals(IDLE, status(order));
        tick(3000);
        assertEquals(reads + 2, grid.storageReads, "Both requests became one read");
        tick(MINUTE);
        assertEquals(reads + 2, grid.storageReads);
    }

    @Test
    void anOrderWaitingForAThrottledReadShowsTheWaitingStatusUntilItArrives() {
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStockSampleIntervalSeconds(900);
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        grid.stock("so-extra", 50);
        StableKey first = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        engine.requestRun(gridKey, first);
        tick(0);
        tick(1000);
        int reads = grid.storageReads;

        StableKey second = post("Extra", below("so-extra", 100), fixed("so-plastic", 64));
        tick(0);
        assertEquals(WAITING_FOR_STOCK, status(second));
        assertEquals(StandingOrderReason.WAITING_READ_FOR_NEW_RESOURCES, snapshot(second).reason());
        tick(1000);
        tick(26_000);
        assertEquals(WAITING_FOR_STOCK, status(second), "The read is deferred, never refused");
        assertEquals(reads, grid.storageReads);
        tick(3000);
        assertEquals(reads + 1, grid.storageReads);
        assertEquals(CALCULATING, status(second));
    }

    @Test
    void aSubmissionThatThrowsIsReportedAsACodeAndBacksOff() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> { throw new IllegalStateException("native submit failure with details"); };
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(SUBMIT_FAILED, status(order));
        assertEquals(StandingOrderReason.SUBMIT_ERROR, snapshot(order).reason());
        assertNull(snapshot(order).submitError(), "Exception text is logged, not published");
        assertEquals(now + 30_000, snapshot(order).nextCheckAt());
    }

    @Test
    void anOrderEnabledAgainAfterItsJobFinishedWhileItWasOffWaitsForAReadTakenAfterwardsButServesNoCooldown() {
        // A tracked network keeps its stock feed while the only order is off.
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStorageTracked(true);
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 3600);
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(order));
        // Past the spacing of the extra read that gave the order its first stock.
        tick(30_000);

        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), false, 3600);
        tick(0);
        assertEquals(DISABLED, status(order));
        cpu.finish();
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 3600);
        tick(0);
        assertEquals(WAITING_FOR_STOCK, status(order), "The stock read in hand may predate the end of the job");
        assertEquals(StandingOrderReason.WAITING_FRESH_READ_AFTER_JOB, snapshot(order).reason());
        assertEquals(1, grid.calculations.size(), "Nothing is calculated before a later read");
        int reads = grid.storageReads;

        tick(1000);
        assertEquals(reads + 1, grid.storageReads, "The read it waits for is requested, not left to the next sample");
        assertEquals(CALCULATING, status(order), "That read releases the order, and no cooldown was served");
        assertEquals(2, grid.calculations.size());
    }

    @Test
    void anOrderEnabledAgainWhileItsJobStillRunsKeepsCraftingAndServesItsCooldownAtTheEnd() {
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStorageTracked(true);
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 3600);
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), false, 3600);
        tick(0);
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 3600);
        tick(0);
        assertEquals(CRAFTING, status(order));
        cpu.finish();
        tick(MINUTE);
        assertEquals(COOLDOWN, status(order), "The job ended while the order was on, so its cooldown applies");
    }

    @Test
    void aStockTrackedNetworkWithOrdersIsReadOnceAtServerStart() {
        GRID_IDENTITIES.getPersistentData(gridKey)
            .getSettings()
            .setStorageTracked(true);
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        // The server applies the stored orders before the first read, which the tracker takes in the same tick.
        now += 1000;
        engine.applyPosted(now);
        coordinator.tick(ae, now);
        engine.tick(now);
        assertEquals(1, grid.storageReads);
        assertEquals(IDLE, status(order), "That first sample is the baseline");
        tick(1000);
        tick(1000);
        assertEquals(1, grid.storageReads, "Nobody asked for a second read");
        tick(MINUTE);
        assertEquals(2, grid.storageReads);
    }

    @Test
    void runNowNeverReadsForADisabledOrderAndNeverOverridesTheTrigger() {
        grid.addCpu();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        StableKey disabled = post("Off", below("so-plastic", 1000), fixed("so-plastic", 64), false, 0);
        StableKey idle = post("Idle", below("so-plastic", 1000), fixed("so-plastic", 64), true, 0);
        tick(0);
        tick(1000);
        int reads = grid.storageReads;
        engine.requestRun(gridKey, disabled);
        engine.requestRun(gridKey, StableKey.random());
        tick(0);
        tick(1000);
        assertEquals(reads, grid.storageReads);
        engine.requestRun(gridKey, idle);
        tick(0);
        tick(1000);
        assertEquals(reads + 1, grid.storageReads);
        assertEquals(IDLE, status(idle));
        assertTrue(grid.calculations.isEmpty());
    }

    @Test
    void aNetworkRunsAtMostTheConfiguredNumberOfCalculationsAndStartsTheWaitingOrderWhenOneEnds() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.stock("so-circuit", 100);
        grid.craftable("so-circuit");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey first = post("Plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        StableKey second = post("Circuit", below("so-circuit", 1000), fixed("so-circuit", 16));
        tick(0);
        tick(1000);
        assertEquals(CALCULATING, status(first));
        assertEquals(QUEUED, status(second));
        assertEquals(StandingOrderReason.CALCULATION_SLOTS_FULL, snapshot(second).reason());
        assertEquals(1, grid.calculations.size());
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(first));
        assertEquals(CALCULATING, status(second));
        assertEquals(2, grid.calculations.size());
        assertEquals(16, grid.calculations.get(1).amount);
    }

    @Test
    void evaluationIsBoundedByTheTickBudgetAndKeepsArrivalOrder() {
        long[] nanos = { 0 };
        clock = () -> nanos[0] += 400_000;
        engine.close();
        engine = newEngine();
        grid.stock("so-plastic", 5000);
        grid.craftable("so-plastic");
        List<StableKey> posted = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            posted.add(post("Order " + index, below("so-plastic", 1000), fixed("so-plastic", 64)));
        }
        tick(0);
        for (int index = 0; index < 3; index++) assertNotNull(engine.snapshot(posted.get(index)));
        for (int index = 3; index < 5; index++) assertNull(engine.snapshot(posted.get(index)));
        tick(0);
        for (StableKey order : posted) assertNotNull(engine.snapshot(order));
    }

    @Test
    void completedCalculationsShareTheEvaluationBudgetAcrossTicks() {
        long[] nanos = { 0 };
        clock = () -> nanos[0];
        engine.close();
        engine = newEngine();
        settings.maxCalculationsInFlightPerGrid = 3;
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        List<StableKey> posted = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            posted.add(post("Order " + index, below("so-plastic", 1000), fixed("so-plastic", 64)));
        }
        tick(0);
        tick(1000);
        assertEquals(3, grid.calculations.size());
        for (OrderGrid.Calculation calculation : grid.calculations) {
            FakeJob job = new FakeJob(true);
            job.onSimulation = () -> nanos[0] += 2_000_000;
            calculation.future.complete(job);
        }
        for (int completed = 1; completed <= 3; completed++) {
            tick(0);
            assertEquals(
                completed,
                posted.stream()
                    .filter(order -> status(order) == BLOCKED_MISSING)
                    .count());
            assertEquals(
                3 - completed,
                posted.stream()
                    .filter(order -> status(order) == CALCULATING)
                    .count());
        }
        assertTrue(grid.submissions.isEmpty());
    }

    // ----- Review fixes -----

    @Test
    void aBaselineDoesNotSurviveTheLossOfItsFeed() {
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(WAITING_FOR_CPU, status(order), "The trigger holds on the first read");
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), false, 0);
        tick(0);
        grid.stock("so-plastic", 5000);
        grid.addCpu();
        tick(10 * MINUTE);
        tick(10 * MINUTE);

        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 0);
        tick(0);
        assertEquals(WAITING_FOR_STOCK, status(order), "Hours-old stock must not be evaluated");
        assertTrue(grid.calculations.isEmpty());
        tick(1000);
        assertEquals(IDLE, status(order));
        assertTrue(grid.calculations.isEmpty());
    }

    @Test
    void aFailureInsideTheEngineNeverEscapesTheTickOrBlocksLaterWork() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        engine.post(gridKey, new StandingOrderStore.Entry() {

            @Override
            public @NotNull StableKey key() {
                throw new IllegalStateException("broken entry");
            }

            @Override
            public int ordinal() {
                return 0;
            }

            @Override
            public @NotNull String createdBy() {
                return "admin";
            }
        });
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        assertDoesNotThrow(() -> tick(0));
        assertEquals(WAITING_FOR_STOCK, status(order), "The command behind the broken one is still applied");
        settingsFail = true;
        assertDoesNotThrow(() -> tick(1000));
        settingsFail = false;
        tick(1000);
        assertEquals(CALCULATING, status(order));
    }

    @Test
    void anAmbiguousTriggerResourceIsAnExplicitStatusNotZeroStock() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.stockRow(new Variant("so-trigger", 500, 1));
        StableKey order = post("Keep plastic", below("so-trigger", 100), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(IDLE, status(order));
        grid.stockRow(new Variant("so-trigger", 500, 2));
        tick(MINUTE);
        tick(MINUTE);
        assertEquals(UNAVAILABLE, status(order));
        assertEquals(StandingOrderReason.AMBIGUOUS_RESOURCE, snapshot(order).reason());
        assertEquals(key("so-trigger"), snapshot(order).resource());
        assertTrue(grid.calculations.isEmpty(), "A resource that cannot be read must not look empty");
    }

    @Test
    void aBriefOutageDoesNotEndTheCooldown() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 3600);
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        cpu.finish();
        tick(MINUTE);
        assertEquals(COOLDOWN, status(order));

        grid.controllerState(AEControllerState.NO_CONTROLLER);
        tick(SWEEP);
        assertEquals(GRID_UNAVAILABLE, status(order));
        grid.controllerState(AEControllerState.CONTROLLER_ONLINE);
        tick(SWEEP);
        tick(1000);
        tick(MINUTE);
        assertEquals(COOLDOWN, status(order));
        assertEquals(1, grid.calculations.size());
    }

    @Test
    void twoOrdersForTheSameResourceSubmitOnlyOneJob() {
        settings.maxCalculationsInFlightPerGrid = 2;
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey first = post("First", below("so-plastic", 1000), fixed("so-plastic", 64));
        StableKey second = post("Second", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(2, grid.calculations.size());
        for (OrderGrid.Calculation calculation : grid.calculations) calculation.future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(1, grid.submissions.size());
        assertEquals(CRAFTING, status(first));
        assertEquals(CRAFTING, status(second));
    }

    @Test
    void editingAnOrderKeepsItsJobAndCooldownOnlyWhileTheCraftedResourceIsTheSame() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.stock("so-circuit", 100);
        grid.craftable("so-circuit");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64), true, 3600);
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        long since = snapshot(order).craftingSince();
        assertEquals(CRAFTING, status(order));

        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 128), true, 3600);
        tick(0);
        assertEquals(CRAFTING, status(order));
        assertEquals(since, snapshot(order).craftingSince());
        assertEquals(1, grid.calculations.size());

        cpu.finish();
        tick(MINUTE);
        assertEquals(COOLDOWN, status(order));
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 32), true, 3600);
        tick(0);
        assertEquals(COOLDOWN, status(order));
        assertEquals(1, grid.calculations.size());

        post(order, "Keep circuit", below("so-circuit", 1000), fixed("so-circuit", 16), true, 3600);
        tick(0);
        tick(1000);
        assertEquals(CALCULATING, status(order), "Another resource starts from a clean state");
        assertEquals(16, grid.calculations.get(1).amount);
    }

    @Test
    void aJobOfAnotherResourceIsForgottenWhenAnOrderIsRedirected() {
        OrderGrid.Cpu cpu = grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        grid.stock("so-circuit", 100);
        grid.craftable("so-circuit");
        grid.onSubmit = () -> cpu.craft("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(false));
        tick(1000);
        assertEquals(CRAFTING, status(order));
        post(order, "Keep circuit", below("so-circuit", 1000), fixed("so-circuit", 16), true, 0);
        tick(0);
        tick(1000);
        assertEquals(WAITING_FOR_CPU, status(order));
        assertEquals(0, snapshot(order).craftingSince());
    }

    @Test
    void replacingTheNativeNetworkDuringACalculationCancelsItAndRestartsOnTheNewOne() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        OrderGrid.Calculation first = grid.calculations.get(0);

        OrderGrid replacement = new OrderGrid();
        replacement.addCpu();
        replacement.stock("so-plastic", 100);
        replacement.craftable("so-plastic");
        GRID_IDENTITIES.controllerValidated(replacement);
        assertEquals(gridKey, GRID_IDENTITIES.getKey(replacement));
        ae = TestGridFixtures.ae(replacement);
        tick(SWEEP);
        assertTrue(first.future.isCancelled(), "A calculation of another native network object is obsolete");
        tick(1000);
        tick(1000);
        assertEquals(CALCULATING, status(order));
        assertEquals(1, replacement.calculations.size());
        assertSame(replacement, replacement.calculations.get(0).grid);
    }

    @Test
    void anEditClearsTheBackoffOfTheFailureItMayHaveFixed() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(true));
        tick(0);
        assertEquals(BLOCKED_MISSING, status(order));
        post(order, "Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 32), true, 0);
        tick(0);
        assertEquals(CALCULATING, status(order));
        assertEquals(32, grid.calculations.get(1).amount);
    }

    private void rename(StableKey order, int storedOrdinal, String name) {
        engine.post(
            gridKey,
            new StandingOrderStore.Valid(
                order,
                storedOrdinal,
                "admin",
                new StandingOrderDefinition(name, true, below("so-plastic", 1000), fixed("so-plastic", 64), 0)));
    }

    @Test
    void renamingAnOrderKeepsItsRunningCalculationAndTheNewNameIsUsedForTheSubmission() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        int stored = ordinal;
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        assertEquals(CALCULATING, status(order));
        OrderGrid.Calculation calculation = grid.calculations.get(0);
        rename(order, stored, "Renamed");
        tick(0);
        assertEquals(CALCULATING, status(order));
        assertFalse(calculation.future.isCancelled());
        assertEquals(1, grid.calculations.size());
        calculation.future.complete(new FakeJob(false));
        tick(0);
        assertEquals("Renamed", grid.submissions.get(0).requester.orderName());
    }

    @Test
    void renamingAnOrderKeepsTheBackoffOfItsFailureButAnyOtherEditClearsIt() {
        grid.addCpu();
        grid.stock("so-plastic", 100);
        grid.craftable("so-plastic");
        int stored = ordinal;
        StableKey order = post("Keep plastic", below("so-plastic", 1000), fixed("so-plastic", 64));
        tick(0);
        tick(1000);
        grid.calculations.get(0).future.complete(new FakeJob(true));
        tick(0);
        assertEquals(BLOCKED_MISSING, status(order));
        rename(order, stored, "Renamed");
        tick(0);
        tick(1000);
        assertEquals(BLOCKED_MISSING, status(order));
        assertEquals(1, grid.calculations.size(), "The backoff still holds");
        post(order, "Renamed", below("so-plastic", 1000), fixed("so-plastic", 32), true, 0);
        tick(0);
        assertEquals(CALCULATING, status(order));
        assertEquals(2, grid.calculations.size());
    }

    private static final class Variant extends ItemIdentityRequestTest.Resource {

        private final int variant;

        private Variant(String id, long quantity, int variant) {
            super(id, quantity, false);
            this.variant = variant;
        }

        @Override
        public @NotNull IAEKey web$copyIdentity() {
            return new Variant(id, 0, variant);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Variant && id.equals(((Variant) other).id) && variant == ((Variant) other).variant;
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    // ----- Fixtures -----

    private void tick(long millis) {
        now += millis;
        coordinator.tick(ae, now);
        engine.tick(now);
    }

    /** Advances only the engine, so that what the tracking coordinator does with the network does not interfere. */
    private void engineTick(long millis) {
        now += millis;
        engine.tick(now);
    }

    private @NotNull StandingOrderStatus status(StableKey order) {
        return snapshot(order).status();
    }

    private @NotNull StandingOrderSnapshot snapshot(StableKey order) {
        StandingOrderSnapshot snapshot = engine.snapshot(order);
        assertNotNull(snapshot, "The order has not been evaluated");
        return snapshot;
    }

    private static StableKey key(String id) {
        return new ItemIdentityRequestTest.Resource(id, 0, false).web$getKey();
    }

    private static Condition below(String id, long amount) {
        return new Condition.StockBelow(key(id), amount);
    }

    private static CraftAction fixed(String id, long batch) {
        return new CraftAction(key(id), new AmountPolicy.Fixed(batch), PLAIN, CpuTarget.any());
    }

    private StableKey post(String name, Condition trigger, CraftAction action) {
        return post(name, trigger, action, true, 0);
    }

    private StableKey post(String name, Condition trigger, CraftAction action, boolean enabled, long cooldown) {
        StableKey order = StableKey.random();
        post(order, name, trigger, action, enabled, cooldown);
        return order;
    }

    private void post(StableKey order, String name, Condition trigger, CraftAction action, boolean enabled,
        long cooldown) {
        engine.post(
            gridKey,
            new StandingOrderStore.Valid(
                order,
                ordinal++,
                "admin",
                new StandingOrderDefinition(name, enabled, trigger, action, cooldown)));
    }

    private static final class FakeJob implements IAECraftingJob {

        private final boolean simulation;
        private final List<ICraftingPlanSummaryEntry> entries;
        private int summaries;
        private boolean summaryFails;
        private Runnable onSimulation = () -> {};

        private FakeJob(boolean simulation, ICraftingPlanSummaryEntry... entries) {
            this.simulation = simulation;
            this.entries = Arrays.asList(entries);
        }

        @Override
        public boolean web$isSimulation() {
            onSimulation.run();
            return simulation;
        }

        @Override
        public long web$getByteTotal() {
            return 0;
        }

        @Override
        public ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
            summaries++;
            if (!simulation) throw new AssertionError("Only a blocked calculation is summarized");
            if (summaryFails) throw new IllegalStateException("Native summary failed");
            return () -> entries;
        }
    }

    /**
     * A network with controllable stock, craftable resources, emitters and crafting CPUs that records what is asked of
     * it.
     */
    private static final class OrderGrid extends TestGridFixtures.TestGrid implements IAECraftingGrid, IAEStorageGrid {

        private static final class Calculation {

            private final IAEGrid grid;
            private final IAEKey resource;
            private final long amount;
            private final CraftingOptions options;
            private final CompletableFuture<IAECraftingJob> future = new CompletableFuture<>();

            private Calculation(IAEGrid grid, IAEKey resource, long amount, CraftingOptions options) {
                this.grid = grid;
                this.resource = resource;
                this.amount = amount;
                this.options = options;
            }
        }

        private static final class Submission {

            private final IAECraftingJob job;
            private final @Nullable ICraftingCPUCluster target;
            private final boolean prioritizePower;
            private final CraftingRequester requester;

            private Submission(IAECraftingJob job, @Nullable ICraftingCPUCluster target, boolean prioritizePower,
                CraftingRequester requester) {
                this.job = job;
                this.target = target;
                this.prioritizePower = prioritizePower;
                this.requester = requester;
            }
        }

        private final class Cpu implements ICraftingCPUCluster {

            private final StableKey key = TestGridFixtures.key(930000L + cpus.size());
            private boolean busy;
            private boolean acceptsPlayerJobs = true;
            private @Nullable IAEGenericStack finalOutput;
            private boolean cancelled;

            private void craft(String id) {
                busy = true;
                finalOutput = new ItemIdentityRequestTest.Resource(id, 1, false);
            }

            private void finish() {
                busy = false;
                finalOutput = null;
            }

            @Override
            public @NotNull StableKey web$getKey() {
                return key;
            }

            @Override
            public boolean web$isValidForGrid(@NotNull IAEGrid grid) {
                return true;
            }

            @Override
            public String web$getName() {
                return "CPU";
            }

            @Override
            public long web$getAvailableStorage() {
                return 0;
            }

            @Override
            public long web$getUsedStorage() {
                return 0;
            }

            @Override
            public long web$getCoProcessors() {
                return 0;
            }

            @Override
            public boolean web$isBusy() {
                return busy;
            }

            @Override
            public boolean web$acceptsPlayerJobs() {
                return acceptsPlayerJobs;
            }

            @Override
            public @Nullable CpuSelectionMode web$getSelectionMode() {
                return null;
            }

            @Override
            public void web$cancel() {
                cancelled = true;
            }

            @Override
            public IAEGenericStack web$getFinalOutput() {
                return finalOutput;
            }

            @Override
            public void web$getItemLists(@NotNull IStackList active, @NotNull IStackList pending,
                @NotNull IStackList stored) {
                throw new AssertionError("Standing orders never read CPU contents");
            }

            @Override
            public IStackList web$getWaitingFor() {
                throw new AssertionError("Standing orders never read CPU contents");
            }
        }

        private final List<ItemIdentityRequestTest.Resource> stock = new ArrayList<>();
        private final Set<IAEKey> craftable = new HashSet<>();
        private final Set<IAEKey> emitters = new HashSet<>();
        private final List<Cpu> cpus = new ArrayList<>();
        private final List<Calculation> calculations = new ArrayList<>();
        private final List<Submission> submissions = new ArrayList<>();
        private @Nullable Runnable onSubmit;
        private @Nullable String submitError;
        private boolean failReads;
        private int storageReads;
        private int cpuReads;
        private int emitterQueries;
        private int pathingProbes;
        private @Nullable StorageCapture capture;

        private OrderGrid() {
            super(920001, false, AEControllerState.CONTROLLER_ONLINE);
        }

        private Cpu addCpu() {
            Cpu cpu = new Cpu();
            cpus.add(cpu);
            return cpu;
        }

        private void stock(String id, long quantity) {
            stock.removeIf(row -> row.id.equals(id));
            stock.add(new ItemIdentityRequestTest.Resource(id, quantity, false));
        }

        private void stockRow(ItemIdentityRequestTest.Resource row) {
            stock.add(row);
        }

        private void craftable(String id) {
            craftable.add(new ItemIdentityRequestTest.Resource(id, 0, true));
        }

        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return this;
        }

        @Override
        public IAEPathingGrid web$getPathingGrid() {
            pathingProbes++;
            return super.web$getPathingGrid();
        }

        @Override
        public IAEStorageGrid web$getStorageGrid() {
            return this;
        }

        @Override
        public boolean web$isCurrentlyCraftable(IAEKey key) {
            return craftable.contains(key);
        }

        @Override
        public boolean web$canEmitFor(IAEKey key) {
            emitterQueries++;
            return emitters.contains(key);
        }

        @Override
        public int web$getCPUCount() {
            return cpus.size();
        }

        @Override
        public Set<ICraftingCPUCluster> web$getCPUs() {
            cpuReads++;
            return new HashSet<>(cpus);
        }

        @Override
        public Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey key, long amount,
            @NotNull CraftingOptions options) {
            Calculation calculation = new Calculation(grid, key, amount, options);
            calculations.add(calculation);
            return calculation.future;
        }

        @Override
        public String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower,
            IAEGrid grid) {
            submissions.add(new Submission(job, target, prioritizePower, CraftingRequesterContext.resolve(null)));
            if (submitError != null) return submitError;
            if (onSubmit != null) onSubmit.run();
            return null;
        }

        @Override
        public Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter) {
            return Collections.unmodifiableSet(craftable);
        }

        @Override
        public IStackList web$getStorageList() {
            storageReads++;
            if (failReads) throw new IllegalStateException("Native read failed");
            List<IAEGenericStack> rows = new ArrayList<>(stock);
            return new IStackList() {

                @Override
                public long web$getAmount(IAEKey key) {
                    long total = 0;
                    for (ItemIdentityRequestTest.Resource row : stock) if (row.equals(key)) total += row.quantity;
                    return total;
                }

                @Override
                public Iterable<IAEGenericStack> web$stacks() {
                    return rows;
                }
            };
        }

        @Override
        public IAEMeInventoryItem web$getInventory() {
            throw new AssertionError("Standing orders never read the inventory");
        }

        @Override
        public @Nullable StorageCapture web$getStorageCapture() {
            return capture;
        }

        @Override
        public void web$setStorageCapture(@Nullable StorageCapture value) {
            capture = value;
        }
    }
}
