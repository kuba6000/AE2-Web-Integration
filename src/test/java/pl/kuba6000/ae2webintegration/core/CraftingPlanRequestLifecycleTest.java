package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
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
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.AEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

class CraftingPlanRequestLifecycleTest extends GridTestScope {

    private static final StableKey GRID_KEY = TestGridFixtures.key(900_001L);

    @ParameterizedTest
    @ValueSource(ints = { 7, -1, -2 })
    void webSubmissionUsesTheSubmittingPrincipalInsteadOfTheSyntheticNativePlayer(int principal) {
        TestCraftingGrid crafting = new TestCraftingGrid(null);
        TestGrid grid = new TestGrid(GRID_KEY, crafting);
        TestGridFixtures.track(grid);
        ICraftingCPUCluster cpu = cpu();
        PlayerIdentity synthetic = new PlayerIdentity(AE2Controller.AEControllerUUID, "AE2CONTROLLER");
        crafting.submitAction = () -> AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, false, true, synthetic);
        assertStatus("OK", submit(grid, plan(grid), TestGridFixtures.principal(principal)));
        List<CraftingRequester> requesters = AE2JobTracker.findActiveJob(cpu)
            .getRequesters();
        if (principal < 0) assertEquals(Collections.singletonList(CraftingRequester.web()), requesters);
        else {
            assertEquals(1, requesters.size());
            assertEquals(
                TestGridFixtures.playerIdentity(principal).uuid,
                requesters.get(0)
                    .player().uuid);
            assertEquals(
                TestGridFixtures.playerIdentity(principal).name,
                requesters.get(0)
                    .player().name);
        }
    }

    @Test
    void mergedWebPlayerAndAutomationSubmissionsRetainEachSourceOnce() {
        TestCraftingGrid crafting = new TestCraftingGrid(null);
        TestGrid grid = new TestGrid(GRID_KEY, crafting);
        TestGridFixtures.track(grid);
        ICraftingCPUCluster cpu = cpu();
        crafting.submitAction = () -> AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, false, true, null);
        assertStatus("OK", submit(grid, plan(grid), TestGridFixtures.principal(7)));
        crafting.submitAction = () -> AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, true, true, null);
        assertStatus("OK", submit(grid, plan(grid), WebPrincipal.admin()));
        assertStatus("OK", submit(grid, plan(grid), WebPrincipal.admin()));
        AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, true, false, null);
        AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, true, false, null);
        List<CraftingRequester> requesters = AE2JobTracker.findActiveJob(cpu)
            .getRequesters();
        assertEquals(3, requesters.size());
        assertEquals(
            TestGridFixtures.playerIdentity(7).uuid,
            requesters.get(0)
                .player().uuid);
        assertEquals(CraftingRequester.web(), requesters.get(1));
        assertEquals(CraftingRequester.automation(), requesters.get(2));
    }

    @Test
    void nestedAndFailedWebSubmissionsRestoreTheCallingRequesterAndThenNativeAttribution() {
        TestCraftingGrid outerCrafting = new TestCraftingGrid(null);
        TestGrid outer = new TestGrid(GRID_KEY, outerCrafting);
        TestGridFixtures.track(outer);
        TestCraftingGrid innerCrafting = new TestCraftingGrid(null);
        TestGrid inner = new TestGrid(TestGridFixtures.key(900_002L), innerCrafting);
        TestGridFixtures.track(inner);
        PlayerIdentity nativePlayer = new PlayerIdentity(UUID.randomUUID(), "NativePlayer");
        ICraftingCPUCluster innerCpu = cpu();
        ICraftingCPUCluster outerCpu = cpu();
        ICraftingCPUCluster laterCpu = cpu();
        innerCrafting.submitAction = () -> {
            AEMixinCallbacks.INSTANCE.jobStarted(innerCpu, inner, false, true, nativePlayer);
            throw new IllegalStateException("Inner submission failed");
        };
        int innerPlan = plan(inner);
        outerCrafting.submitAction = () -> {
            assertThrows(IllegalStateException.class, () -> submit(inner, innerPlan, WebPrincipal.admin()));
            AEMixinCallbacks.INSTANCE.jobStarted(outerCpu, outer, false, true, nativePlayer);
            throw new IllegalStateException("Outer submission failed");
        };
        assertThrows(IllegalStateException.class, () -> submit(outer, plan(outer), TestGridFixtures.principal(7)));
        assertEquals(
            Collections.singletonList(CraftingRequester.web()),
            AE2JobTracker.findActiveJob(innerCpu)
                .getRequesters());
        assertEquals(
            TestGridFixtures.playerIdentity(7).uuid,
            AE2JobTracker.findActiveJob(outerCpu)
                .getRequesters()
                .get(0)
                .player().uuid);
        AEMixinCallbacks.INSTANCE.jobStarted(laterCpu, outer, false, true, nativePlayer);
        assertEquals(
            Collections.singletonList(CraftingRequester.player(nativePlayer)),
            AE2JobTracker.findActiveJob(laterCpu)
                .getRequesters());
    }

    private static int plan(TestGrid grid) {
        return GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(new TestCraftingJob()),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
    }

    private static ICraftingCPUCluster cpu() {
        return (ICraftingCPUCluster) Proxy.newProxyInstance(
            CraftingPlanRequestLifecycleTest.class.getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("web$getFinalOutput"))
                    return new OutputSnapshotTest.Stack(new OutputSnapshotTest.Resource(), 5);
                throw new AssertionError("Unexpected CPU operation: " + method.getName());
            });
    }

    @Test
    void aSuccessfullySubmittedPlanCannotBeSubmittedAgain() {
        TestCraftingGrid crafting = new TestCraftingGrid(null);
        TestGrid grid = new TestGrid(GRID_KEY, crafting);
        GridData gridData = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid));
        int id = gridData.addJob(
            CompletableFuture.completedFuture(new TestCraftingJob()),
            TestGridFixtures.planOutput(),
            new CraftingOptions(false, false),
            false);

        assertStatus("OK", submit(grid, id));
        assertStatus("INVALID_ID", submit(grid, id));
    }

    @Test
    void aFailedSubmissionKeepsThePlanAvailableForRetry() {
        TestCraftingGrid crafting = new TestCraftingGrid("Submission failed");
        TestGrid grid = new TestGrid(GRID_KEY, crafting);
        int id = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(new TestCraftingJob()),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);

        assertStatus("FAIL", submit(grid, id));
        assertStatus("FAIL", submit(grid, id));
    }

    @Test
    void deletingAPendingPlanCancelsCalculationAndRemovesIt() {
        TestGrid grid = new TestGrid(GRID_KEY, new TestCraftingGrid(null));
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        int id = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(pending, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
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
            .addJob(new CompletableFuture<>(), TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        GetCraftingPlan poll = new GetCraftingPlan();
        assertTrue(
            poll.init(TestGridFixtures.context(-1, "grid=" + CoreEngine.GRID_IDENTITIES.getKey(grid) + "&id=" + id)));
        poll.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            HttpURLConnection.HTTP_OK,
            poll.getResponse()
                .httpStatus());
        assertFalse(
            new JsonParser().parse(poll.getJSON())
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

    @Test
    void calculatedResourceSerializesCamelCaseIdentityAndMeasurements() {
        GetCraftingPlan.PlanData.JobItem row = new GetCraftingPlan.PlanData.JobItem(
            "example",
            "resource",
            "Resource",
            2,
            7,
            ResourceType.ITEM,
            8,
            12,
            3,
            4,
            0.5,
            null,
            null);
        JsonObject json = new JsonParser().parse(
            GSONUtils.GSON_BUILDER.create()
                .toJson(row))
            .getAsJsonObject();
        assertEquals(
            "resource",
            json.get("registryPath")
                .getAsString());
        assertEquals(
            "Resource",
            json.get("displayName")
                .getAsString());
        assertEquals(
            8,
            json.get("stored")
                .getAsLong());
        assertEquals(
            12,
            json.get("requested")
                .getAsLong());
        assertEquals(
            3,
            json.get("missing")
                .getAsLong());
        assertEquals(
            4,
            json.get("steps")
                .getAsLong());
        assertEquals(
            0.5,
            json.get("usedPercent")
                .getAsDouble());
        assertEquals(
            "example",
            json.get("registryNamespace")
                .getAsString());
        assertEquals(
            2,
            json.get("componentCount")
                .getAsInt());
        assertEquals(
            7,
            json.get("damage")
                .getAsInt());
        assertFalse(json.has("itemId"));
        assertFalse(json.has("itemName"));
        assertFalse(json.has("itemid"));
        assertFalse(json.has("itemname"));
    }

    private static String submit(TestGrid grid, int id) {
        return submit(grid, id, WebPrincipal.admin());
    }

    private static String submit(TestGrid grid, int id, WebPrincipal principal) {
        SubmitCraftingPlan request = new SubmitCraftingPlan();
        request.init(
            TestGridFixtures
                .context(principal, "grid=" + CoreEngine.GRID_IDENTITIES.getKey(grid) + "&id=" + id + "&submit"));
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

    private static final class TestCraftingGrid implements IAECraftingGrid {

        private final String submitResult;
        private Runnable submitAction = () -> {};

        private TestCraftingGrid(String submitResult) {
            this.submitResult = submitResult;
        }

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
        public Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey key, long amount,
            @NotNull CraftingOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean prioritizePower,
            IAEGrid grid) {
            submitAction.run();
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
