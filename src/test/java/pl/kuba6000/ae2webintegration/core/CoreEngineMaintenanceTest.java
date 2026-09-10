package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CoreEngineMaintenanceTest {

    private static final long GRID_KEY = 900_301L;

    @Test
    void idleServerMaintenanceEventuallyEvictsAnAbandonedCompletedPlan() {
        CoreEngine.onServerStopped();
        GridData gridData = GridData.getOrCreate(GRID_KEY);
        CompletableFuture<IAECraftingJob> plan = CompletableFuture.completedFuture(null);
        int id = gridData.addJob(plan);
        assertSame(plan, gridData.getJob(id));

        long afterRetention = System.nanoTime() + TimeUnit.MINUTES.toNanos(16);
        for (int i = 0; i < 100; i++) {
            CoreEngine.runPlanMaintenance(afterRetention);
        }

        assertNull(gridData.getJob(id));
    }

    @Test
    void maintenanceNeverAgeEvictsAPlanThatIsStillCalculating() {
        CoreEngine.onServerStopped();
        GridData gridData = GridData.getOrCreate(GRID_KEY + 1);
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        int id = gridData.addJob(pending);

        long muchLater = System.nanoTime() + TimeUnit.DAYS.toNanos(1);
        for (int i = 0; i < 100; i++) {
            CoreEngine.runPlanMaintenance(muchLater);
        }

        assertSame(pending, gridData.getJob(id));
    }

    @Test
    void aLargeGridSetIsSweptInSmallResumableBatches() {
        CoreEngine.onServerStopped();
        List<GridData> grids = new ArrayList<>();
        List<Integer> planIds = new ArrayList<>();
        int planCount = CoreEngine.PLAN_SWEEP_GRIDS_PER_TICK + 1;
        for (int i = 0; i < planCount; i++) {
            GridData gridData = GridData.getOrCreate(GRID_KEY + 100 + i);
            int id = gridData.addJob(CompletableFuture.completedFuture(null));
            gridData.getJob(id);
            grids.add(gridData);
            planIds.add(id);
        }

        long afterRetention = System.nanoTime() + TimeUnit.MINUTES.toNanos(16);
        CoreEngine.runPlanMaintenance(afterRetention);

        int remaining = 0;
        for (int i = 0; i < planCount; i++) {
            if (grids.get(i)
                .getJob(planIds.get(i)) != null) {
                remaining++;
            }
        }
        assertTrue(remaining > 0, "one tick must not scan more grids than the fixed batch size");

        for (int i = 0; i < 100; i++) {
            CoreEngine.runPlanMaintenance(afterRetention);
        }
        for (int i = 0; i < planCount; i++) {
            assertNull(
                grids.get(i)
                    .getJob(planIds.get(i)));
        }
    }
}
