package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.DeleteCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.SubmitCraftingPlan;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

class CraftingPlanRequestLifecycleTest extends GridTestScope {

    private static final StableKey GRID_KEY = TestGridFixtures.key(900_001L);

    @Test
    void aSuccessfullySubmittedPlanCannotBeSubmittedAgain() {
        TestCraftingGrid crafting = new TestCraftingGrid(null);
        TestGrid grid = new TestGrid(GRID_KEY, crafting);
        GridData gridData = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid));
        int id = gridData.addJob(CompletableFuture.completedFuture(new TestCraftingJob()));

        assertStatus("OK", submit(grid, id));
        assertStatus("INVALID_ID", submit(grid, id));
    }

    @Test
    void aFailedSubmissionKeepsThePlanAvailableForRetry() {
        TestCraftingGrid crafting = new TestCraftingGrid("Submission failed");
        TestGrid grid = new TestGrid(GRID_KEY, crafting);
        int id = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(CompletableFuture.completedFuture(new TestCraftingJob()));

        assertStatus("FAIL", submit(grid, id));
        assertStatus("FAIL", submit(grid, id));
    }

    @Test
    void deletingAPendingPlanCancelsCalculationAndRemovesIt() {
        TestGrid grid = new TestGrid(GRID_KEY, new TestCraftingGrid(null));
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        int id = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(pending);
        DeleteCraftingPlan request = new DeleteCraftingPlan();
        assertTrue(
            request
                .init(TestGridFixtures.context(-1, "grid=" + CoreEngine.GRID_IDENTITIES.getKey(grid) + "&id=" + id)));
        request.runOnServerThread(TestGridFixtures.ae(grid));
        assertStatus("OK", request.getJSON());
        assertEquals(
            HttpURLConnection.HTTP_OK,
            request.getResponse()
                .httpStatus());
        assertTrue(pending.isCancelled());
        GetCraftingPlan poll = new GetCraftingPlan();
        assertTrue(
            poll.init(TestGridFixtures.context(-1, "grid=" + CoreEngine.GRID_IDENTITIES.getKey(grid) + "&id=" + id)));
        poll.runOnServerThread(TestGridFixtures.ae(grid));
        assertStatus("INVALID_ID", poll.getJSON());
        assertEquals(
            HttpURLConnection.HTTP_NOT_FOUND,
            poll.getResponse()
                .httpStatus());
    }

    @Test
    void pollingPendingCalculationSucceedsButSubmissionConflicts() {
        TestGrid grid = new TestGrid(GRID_KEY, new TestCraftingGrid(null));
        int id = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(new CompletableFuture<>());
        GetCraftingPlan poll = new GetCraftingPlan();
        assertTrue(
            poll.init(TestGridFixtures.context(-1, "grid=" + CoreEngine.GRID_IDENTITIES.getKey(grid) + "&id=" + id)));
        poll.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            HttpURLConnection.HTTP_OK,
            poll.getResponse()
                .httpStatus());
        assertFalse(
            JsonParser.parseString(poll.getJSON())
                .getAsJsonObject()
                .getAsJsonObject("data")
                .get("isDone")
                .getAsBoolean());
        SubmitCraftingPlan submit = new SubmitCraftingPlan();
        assertTrue(
            submit.init(
                TestGridFixtures
                    .context(-1, "grid=" + CoreEngine.GRID_IDENTITIES.getKey(grid) + "&id=" + id + "&submit")));
        submit.runOnServerThread(TestGridFixtures.ae(grid));
        assertStatus("JOB_NOT_DONE", submit.getJSON());
        assertEquals(
            HttpURLConnection.HTTP_CONFLICT,
            submit.getResponse()
                .httpStatus());
    }

    private static String submit(TestGrid grid, int id) {
        SubmitCraftingPlan request = new SubmitCraftingPlan();
        request.init(
            TestGridFixtures.context(-1, "grid=" + CoreEngine.GRID_IDENTITIES.getKey(grid) + "&id=" + id + "&submit"));
        request.runOnServerThread(TestGridFixtures.ae(grid));
        return request.getJSON();
    }

    private static void assertStatus(String expected, String json) {
        assertTrue(
            json.contains("\"status\":\"" + expected + "\""),
            "expected status " + expected + " but got " + json);
    }

    private static final class TestGrid extends TestGridFixtures.TestGrid {

        private final IAECraftingGrid crafting;

        private TestGrid(StableKey securityKey, IAECraftingGrid crafting) {
            super(securityKey, false, AEControllerState.CONTROLLER_ONLINE);
            this.crafting = crafting;
        }

        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return crafting;
        }
    }

    @Desugar
    private record TestCraftingGrid(String submitResult) implements IAECraftingGrid {

        @Override
        public boolean web$isCurrentlyCraftable(IAEKey key) {
            throw new AssertionError("Existing plan lifecycle must not start another order");
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
