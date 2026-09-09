package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

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
        public IAECraftingJob get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public IAECraftingJob get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }

    @Test
    void aPendingPlanIsNeverEvictedMerelyBecauseTimePassed() {
        AtomicLong clock = new AtomicLong();
        CraftingPlanRegistry registry = new CraftingPlanRegistry(clock::get);
        CompletableFuture<IAECraftingJob> plan = new CompletableFuture<>();
        int id = registry.add(plan);

        clock.set(TimeUnit.DAYS.toNanos(1));
        registry.evictExpiredCompleted();

        assertSame(plan, registry.find(id));
        assertFalse(plan.isCancelled());
    }

    @Test
    void aCompletedPlanGetsTheFullRetentionWindowAfterItIsFirstObserved() {
        AtomicLong clock = new AtomicLong();
        CraftingPlanRegistry registry = new CraftingPlanRegistry(clock::get);
        CompletedRecordingFuture plan = new CompletedRecordingFuture();
        int id = registry.add(plan);

        clock.set(TimeUnit.DAYS.toNanos(1));
        registry.evictExpiredCompleted();
        assertSame(plan, registry.find(id), "a long calculation must not consume the retention window");

        clock.addAndGet(TimeUnit.MINUTES.toNanos(15));
        registry.evictExpiredCompleted();
        assertNull(registry.find(id));
        assertFalse(plan.cancelCalled, "age eviction only releases the completed result");
    }

    @Test
    void accessingACompletedPlanRefreshesItsInactivityWindow() {
        AtomicLong clock = new AtomicLong();
        CraftingPlanRegistry registry = new CraftingPlanRegistry(clock::get);
        CompletableFuture<IAECraftingJob> plan = CompletableFuture.completedFuture(null);
        int id = registry.add(plan);
        registry.evictExpiredCompleted();

        clock.set(TimeUnit.MINUTES.toNanos(14));
        assertSame(plan, registry.find(id));

        clock.set(TimeUnit.MINUTES.toNanos(15));
        registry.evictExpiredCompleted();
        assertSame(plan, registry.find(id), "recently accessed plans must remain retryable");

        clock.set(TimeUnit.MINUTES.toNanos(30));
        registry.evictExpiredCompleted();
        assertNull(registry.find(id));
    }

    @Test
    void explicitCancellationCancelsAndRemovesThePlan() {
        CraftingPlanRegistry registry = new CraftingPlanRegistry(() -> 0L);
        CompletableFuture<IAECraftingJob> plan = new CompletableFuture<>();
        int id = registry.add(plan);

        assertTrue(registry.cancel(id));

        assertTrue(plan.isCancelled());
        assertNull(registry.find(id));
    }

    @Test
    void serverStopCancelsPendingWorkAndDropsAllRuntimePlanState() {
        CraftingPlanRegistry registry = new CraftingPlanRegistry(() -> 0L);
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        CompletableFuture<IAECraftingJob> completed = CompletableFuture.completedFuture(null);
        int pendingId = registry.add(pending);
        int completedId = registry.add(completed);

        registry.clearForServerStop();

        assertTrue(pending.isCancelled());
        assertFalse(completed.isCancelled());
        assertNull(registry.find(pendingId));
        assertNull(registry.find(completedId));
        assertEquals(1, registry.add(new CompletableFuture<>()), "a new world gets a fresh runtime id space");
    }

    @Test
    void onlyTheThreeNewestPlansRemainAddressable() {
        CraftingPlanRegistry registry = new CraftingPlanRegistry(() -> 0L);
        CompletableFuture<IAECraftingJob> oldest = new CompletableFuture<>();
        int first = registry.add(oldest);
        int second = registry.add(new CompletableFuture<>());
        int third = registry.add(new CompletableFuture<>());
        int fourth = registry.add(new CompletableFuture<>());

        assertNull(registry.find(first));
        assertFalse(oldest.isCancelled(), "capacity eviction preserves the existing non-cancelling behavior");
        assertNotNull(registry.find(second));
        assertNotNull(registry.find(third));
        assertNotNull(registry.find(fourth));
    }
}
