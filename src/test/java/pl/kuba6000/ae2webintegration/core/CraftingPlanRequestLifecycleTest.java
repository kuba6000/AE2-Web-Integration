package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.Job;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

class CraftingPlanRequestLifecycleTest {

    private static final long GRID_KEY = 900_001L;

    @Test
    void aSuccessfullySubmittedPlanCannotBeSubmittedAgain() {
        TestCraftingGrid crafting = new TestCraftingGrid(null);
        TestGrid grid = new TestGrid(GRID_KEY, crafting);
        GridData gridData = GridData.getOrCreate(GRID_KEY);
        int id = gridData.addJob(CompletableFuture.completedFuture(new TestCraftingJob()));

        assertStatus("OK", submit(grid, id));
        assertStatus("INVALID_ID", submit(grid, id));
    }

    @Test
    void aFailedSubmissionKeepsThePlanAvailableForRetry() {
        TestCraftingGrid crafting = new TestCraftingGrid("Submission failed");
        TestGrid grid = new TestGrid(GRID_KEY, crafting);
        int id = GridData.getOrCreate(GRID_KEY)
            .addJob(CompletableFuture.completedFuture(new TestCraftingJob()));

        assertStatus("FAIL", submit(grid, id));
        assertStatus("FAIL", submit(grid, id));
    }

    private static String submit(TestGrid grid, int id) {
        Job request = new Job();
        request.init(TestGridFixtures.context(-1, "grid=" + GRID_KEY + "&id=" + id + "&submit"));
        request.handle(TestGridFixtures.ae(grid));
        return request.getJSON();
    }

    private static void assertStatus(String expected, String json) {
        assertTrue(
            json.contains("\"status\":\"" + expected + "\""),
            "expected status " + expected + " but got " + json);
    }

    private static final class TestGrid extends TestGridFixtures.TestGrid {

        private final IAECraftingGrid crafting;

        private TestGrid(long securityKey, IAECraftingGrid crafting) {
            super(securityKey, true, false, AEControllerState.CONTROLLER_ONLINE);
            this.crafting = crafting;
        }

        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return crafting;
        }
    }

    private static final class TestCraftingGrid implements IAECraftingGrid {

        @Override
        public boolean web$isCurrentlyCraftable(IAEKey key) {
            throw new AssertionError("Existing plan lifecycle must not start another order");
        }

        private final String submitResult;

        private TestCraftingGrid(String submitResult) {
            this.submitResult = submitResult;
        }

        @Override
        public int web$getCPUCount() {
            return 0;
        }

        @Override
        public Set<ICraftingCPUCluster> web$getCPUs() {
            return Collections.emptySet();
        }

        @Override
        public Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey key, long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower,
            IAEGrid grid) {
            return submitResult;
        }

        @Override
        public Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter) {
            return Collections.emptySet();
        }
    }

    private static final class TestCraftingJob implements IAECraftingJob {

        @Override
        public boolean web$isSimulation() {
            return false;
        }

        @Override
        public long web$getByteTotal() {
            return 0;
        }

        @Override
        public ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
            throw new UnsupportedOperationException();
        }
    }
}
