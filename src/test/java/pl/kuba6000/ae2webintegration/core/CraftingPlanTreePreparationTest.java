package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.grid.CraftingPlanRegistry;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingPlanTreePreparationTest {

    @Test
    void viewersShareOneNativeCaptureAndOneDetachedGraphUntilRemoval() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        CraftingPlanRegistry registry = new CraftingPlanRegistry(System::nanoTime);
        int id = registry.add(
            CompletableFuture.completedFuture(job(captures)),
            TestGridFixtures.planOutput(),
            new CraftingOptions(false, false),
            false);
        CraftingPlanRegistry.Entry entry = registry.find(id);
        assertNotNull(entry);
        assertEquals(0, captures.get());
        CraftingPlanRegistry.TreePreparation first = entry.captureTree(20, 1000);
        assertSame(first, entry.captureTree(20, 1000));
        assertEquals(1, captures.get());
        assertSame(first.graph(), first.graph());
        registry.remove(id);
        assertFalse(entry.isRetained());
        assertNull(entry.tree());
    }

    @Test
    void retirementDuringNativeCaptureCannotRestoreTheEntryOwnedGraph() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CraftingPlanRegistry registry = new CraftingPlanRegistry(System::nanoTime);
        int id = registry.add(CompletableFuture.completedFuture(job(() -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            return example();
        })), TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        CraftingPlanRegistry.Entry entry = registry.find(id);
        assertNotNull(entry);
        ExecutorService server = Executors.newSingleThreadExecutor();
        try {
            Future<CraftingPlanRegistry.TreePreparation> captured = server.submit(() -> entry.captureTree(20, 1000));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            registry.cancel(id);
            assertFalse(entry.isRetained());
            release.countDown();
            captured.get(5, TimeUnit.SECONDS);
            assertNull(entry.tree());
            assertNull(registry.find(id));
        } finally {
            release.countDown();
            server.shutdownNow();
        }
    }

    @Test
    void expiryCapacityEvictionAndWorldStopReleasePreparedGraphs() throws Exception {
        AtomicLong clock = new AtomicLong();
        CraftingPlanRegistry registry = new CraftingPlanRegistry(clock::get);
        for (int action = 0; action < 3; action++) {
            int id = registry.add(
                CompletableFuture.completedFuture(job(new AtomicInteger())),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
            CraftingPlanRegistry.Entry entry = registry.find(id);
            assertNotNull(entry);
            entry.captureTree(20, 1000);
            if (action == 0) {
                clock.addAndGet(TimeUnit.MINUTES.toNanos(16));
                registry.evictExpiredCompleted();
            } else if (action == 1) {
                for (int i = 0; i < 3; i++) registry.add(
                    new CompletableFuture<>(),
                    TestGridFixtures.planOutput(),
                    new CraftingOptions(false, false),
                    false);
            } else registry.clearForServerStop();
            assertFalse(entry.isRetained());
            assertNull(entry.tree());
        }
    }

    @Test
    void captureFailureIsCompletedFailureRatherThanPermanentPreparation() {
        CraftingPlanRegistry registry = new CraftingPlanRegistry(System::nanoTime);
        int id = registry.add(
            CompletableFuture.completedFuture(job(() -> { throw new IllegalStateException("native failure"); })),
            TestGridFixtures.planOutput(),
            new CraftingOptions(false, false),
            false);
        CraftingPlanRegistry.Entry entry = registry.find(id);
        assertNotNull(entry);
        assertThrows(IllegalStateException.class, () -> entry.captureTree(20, 1000));
        CraftingPlanRegistry.TreePreparation preparation = entry.tree();
        assertNotNull(preparation);
        assertTrue(preparation.isCaptured());
        assertThrows(CompletionException.class, preparation::graph);
    }

    static IAECraftingJob job(AtomicInteger captures) {
        return job(() -> {
            captures.incrementAndGet();
            return example();
        });
    }

    static CraftingRecipeCapture example() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(20, 1000);
        int resource = capture.addResource(
            () -> TestGridFixtures.planOutput()
                .description());
        capture.result(resource, 9007199254740993L, false);
        int recipe = capture.addRecipe(2);
        capture.addOutput(recipe, resource, 3, true);
        capture.finishRecipe(recipe);
        int alternative = capture.addRecipe(4);
        capture.addOutput(alternative, resource, 1, true);
        capture.finishRecipe(alternative);
        return capture.build();
    }

    static IAECraftingJob job(Supplier<CraftingRecipeCapture> capture) {
        return new IAECraftingJob() {

            public boolean web$isSimulation() {
                return false;
            }

            public long web$getByteTotal() {
                return 0;
            }

            public ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
                throw new AssertionError();
            }

            public CraftingRecipeCapture web$captureRecipes(int entries, int characters) {
                return capture.get();
            }
        };
    }
}
