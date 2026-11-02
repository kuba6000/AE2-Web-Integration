package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture;
import pl.kuba6000.ae2webintegration.core.crafting.TraceFixtures;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

/** Capture completeness and worker reads for native traces. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingPlanTraceRequestTest extends GridTestScope {

    private static final int OWNER = 7;

    @AfterEach
    void clearPlans() {
        GridData.clearRuntimeState();
    }

    @Test
    void aTruncatedTraceIsPartialAndCannotClaimThatNothingIsMissing() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(407);
        int plan = addTrace(grid, () -> {
            CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(4, 1000);
            int resource = trace.addResource(() -> TraceFixtures.descriptor("item"));
            int request = trace.addRequest(resource, 5);
            trace.root(request, false);
            trace.addContribution(request, CraftingTraceCapture.ContributionKind.STORAGE, 1);
            trace.addTask(1);
            trace.addContribution(request, CraftingTraceCapture.ContributionKind.STORAGE, 1);
            return trace.build();
        }, new AtomicInteger());
        JsonObject data = read(grid, plan, "");
        assertEquals(
            "TRACE",
            data.get("mode")
                .getAsString());
        assertTrue(
            data.get("partial")
                .getAsBoolean());
        assertEquals(
            "ENTRIES",
            data.getAsJsonArray("limits")
                .get(0)
                .getAsString());
        assertFalse(
            data.get("missingComplete")
                .getAsBoolean());
    }

    @Test
    void aCapturedTraceIsServedWithoutTheServerStageAndKeepsItsCaptureIdentity() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(409);
        AtomicInteger captures = new AtomicInteger();
        int plan = addTrace(grid, TraceFixtures::gears, captures);
        GetCraftingPlanTree uncaptured = request(grid, plan, "");
        assertFalse(uncaptured.serveWithoutServerStage());
        uncaptured.runOnServerThread(TestGridFixtures.ae(grid));
        String captureId = json(
            uncaptured.finishResponse()
                .json())
            .getAsJsonObject("data")
            .get("captureId")
            .getAsString();
        GetCraftingPlanTree later = request(grid, plan, "captureId=" + captureId);
        assertTrue(later.serveWithoutServerStage());
        JsonObject data = json(
            later.finishResponse()
                .json())
            .getAsJsonObject("data");
        assertEquals(
            "TRACE",
            data.get("mode")
                .getAsString());
        assertEquals(
            captureId,
            data.get("captureId")
                .getAsString());
        assertEquals(1, captures.get());
    }

    private static int addTrace(TestGridFixtures.TestGrid grid, Supplier<CraftingTraceCapture> trace,
        AtomicInteger captures) {
        return addPlan(grid, CraftingPlanTreePreparationTest.traceJob(() -> {
            captures.incrementAndGet();
            return trace.get();
        }, () -> { throw new AssertionError("The aggregate is only the fallback"); }));
    }

    private static int addPlan(TestGridFixtures.TestGrid grid, IAECraftingJob job) {
        return GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(job),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
    }

    private static JsonObject read(TestGridFixtures.TestGrid grid, int plan, String query) {
        GetCraftingPlanTree request = request(grid, plan, query);
        request.runOnServerThread(TestGridFixtures.ae(grid));
        return json(
            request.finishResponse()
                .json())
            .getAsJsonObject("data");
    }

    private static GetCraftingPlanTree request(TestGridFixtures.TestGrid grid, int plan, String query) {
        Map<String, String> path = new HashMap<>();
        path.put(
            "gridKey",
            TestGridFixtures.resolvedKey(grid)
                .toString());
        path.put("planId", Integer.toString(plan));
        GetCraftingPlanTree request = new GetCraftingPlanTree();
        assertTrue(
            request.init(
                new AE2Controller.RequestContext(
                    new TestGridFixtures.TestExchange(query),
                    TestGridFixtures.principal(OWNER),
                    path,
                    null)));
        return request;
    }

    private static JsonObject json(String value) {
        return new JsonParser().parse(value)
            .getAsJsonObject();
    }
}
