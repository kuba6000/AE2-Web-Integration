package pl.kuba6000.ae2webintegration.core;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.grid.CraftingPlanRegistry;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingPlanRegistryTest {

    private static final class CompletedRecordingFuture implements Future<IAECraftingJob> {

        private boolean cancelCalled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalled = true;
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public IAECraftingJob get() {
            return null;
        }

        @Override
        public IAECraftingJob get(long timeout, @NotNull TimeUnit unit) {
            return null;
        }
    }

    @Test
    void aPendingPlanIsNeverEvictedMerelyBecauseTimePassed() {
        AtomicLong clock = new AtomicLong();
        CraftingPlanRegistry registry = new CraftingPlanRegistry(clock::get);
        CompletableFuture<IAECraftingJob> plan = new CompletableFuture<>();
        int id = registry.add(plan, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);

        clock.set(DAYS.toNanos(1));
        registry.evictExpiredCompleted();

        CraftingPlanRegistry.Entry entry = registry.find(id);
        assertNotNull(entry);
        assertSame(plan, entry.plan);
        assertFalse(plan.isCancelled());
    }

    @Test
    void aCompletedPlanGetsTheFullRetentionWindowAfterItIsFirstObserved() {
        AtomicLong clock = new AtomicLong();
        CraftingPlanRegistry registry = new CraftingPlanRegistry(clock::get);
        CompletedRecordingFuture plan = new CompletedRecordingFuture();
        int id = registry.add(plan, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);

        clock.set(DAYS.toNanos(1));
        registry.evictExpiredCompleted();
        CraftingPlanRegistry.Entry entry = registry.find(id);
        assertNotNull(entry);
        assertSame(plan, entry.plan, "a long calculation must not consume the retention window");

        clock.addAndGet(MINUTES.toNanos(15));
        registry.evictExpiredCompleted();
        assertNull(registry.find(id));
        assertFalse(plan.cancelCalled, "age eviction only releases the completed result");
    }

    @Test
    void accessingACompletedPlanRefreshesItsInactivityWindow() {
        AtomicLong clock = new AtomicLong();
        CraftingPlanRegistry registry = new CraftingPlanRegistry(clock::get);
        CompletableFuture<IAECraftingJob> plan = CompletableFuture.completedFuture(null);
        int id = registry.add(plan, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        registry.evictExpiredCompleted();

        clock.set(MINUTES.toNanos(14));
        CraftingPlanRegistry.Entry entry = registry.find(id);
        assertNotNull(entry);
        assertSame(plan, entry.plan);

        clock.set(MINUTES.toNanos(15));
        registry.evictExpiredCompleted();
        CraftingPlanRegistry.Entry retained = registry.find(id);
        assertNotNull(retained);
        assertSame(plan, retained.plan, "recently accessed plans must remain retryable");

        clock.set(MINUTES.toNanos(30));
        registry.evictExpiredCompleted();
        assertNull(registry.find(id));
    }

    @Test
    void explicitCancellationCancelsAndRemovesThePlan() {
        CraftingPlanRegistry registry = new CraftingPlanRegistry(() -> 0L);
        CompletableFuture<IAECraftingJob> plan = new CompletableFuture<>();
        int id = registry.add(plan, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);

        assertTrue(registry.cancel(id));

        assertTrue(plan.isCancelled());
        assertNull(registry.find(id));
    }

    @Test
    void serverStopCancelsPendingWorkAndDropsAllRuntimePlanState() {
        CraftingPlanRegistry registry = new CraftingPlanRegistry(() -> 0L);
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        CompletableFuture<IAECraftingJob> completed = CompletableFuture.completedFuture(null);
        int pendingId = registry.add(pending, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        int completedId = registry
            .add(completed, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);

        registry.clearForServerStop();

        assertTrue(pending.isCancelled());
        assertFalse(completed.isCancelled());
        assertNull(registry.find(pendingId));
        assertNull(registry.find(completedId));
        assertEquals(
            1,
            registry.add(
                new CompletableFuture<>(),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false),
            "a new world gets a fresh runtime id space");
    }

    @Test
    void capacityEvictionCancelsOnlyTheDisplacedPendingPlan() {
        CraftingPlanRegistry registry = new CraftingPlanRegistry(() -> 0L);
        CompletableFuture<IAECraftingJob> oldest = new CompletableFuture<>();
        CompletableFuture<IAECraftingJob> secondPlan = new CompletableFuture<>();
        CompletableFuture<IAECraftingJob> thirdPlan = new CompletableFuture<>();
        CompletableFuture<IAECraftingJob> fourthPlan = new CompletableFuture<>();
        int first = registry.add(oldest, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        CraftingPlanRegistry.Entry displaced = registry.find(first);
        assertNotNull(displaced);
        int second = registry.add(secondPlan, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        int third = registry.add(thirdPlan, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        int fourth = registry.add(fourthPlan, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);

        assertNull(registry.find(first));
        assertFalse(displaced.isRetained());
        assertTrue(oldest.isCancelled());
        assertNotNull(registry.find(second));
        assertNotNull(registry.find(third));
        assertNotNull(registry.find(fourth));
        assertFalse(secondPlan.isCancelled());
        assertFalse(thirdPlan.isCancelled());
        assertFalse(fourthPlan.isCancelled());
    }

    @Test
    void capacityEvictionReleasesACompletedPlanWithoutCancellingIt() {
        CraftingPlanRegistry registry = new CraftingPlanRegistry(() -> 0L);
        CompletedRecordingFuture completed = new CompletedRecordingFuture();
        int first = registry.add(completed, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        CraftingPlanRegistry.Entry displaced = registry.find(first);
        assertNotNull(displaced);
        for (int i = 0; i < 3; i++) {
            registry.add(
                new CompletableFuture<>(),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        }

        assertNull(registry.find(first));
        assertFalse(displaced.isRetained());
        assertFalse(completed.cancelCalled);
    }
}
