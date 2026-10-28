package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.auth.AuthSession;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingPlanTreeRequestTest extends GridTestScope {

    @AfterEach
    void clearPlans() {
        GridData.clearRuntimeState();
    }

    @Test
    void pagesSharedDeclaredProducersWithExactDecimalQuantities() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(55);
        AtomicInteger captures = new AtomicInteger();
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(captures)),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        GetCraftingPlanTree first = request(grid, plan, "limit=1");
        first.runOnServerThread(TestGridFixtures.ae(grid));
        JsonObject page = json(
            first.finishResponse()
                .json())
            .getAsJsonObject("data");
        assertEquals(
            "READY",
            page.get("state")
                .getAsString());
        assertEquals(
            "9007199254740993",
            page.getAsJsonObject("root")
                .get("quantity")
                .getAsString());
        assertTrue(
            page.getAsJsonObject("root")
                .getAsJsonPrimitive("quantity")
                .isString());
        assertEquals(
            1,
            page.getAsJsonArray("children")
                .size());
        assertEquals(
            1,
            page.get("nextOffset")
                .getAsInt());
        String token = page.get("captureId")
            .getAsString();
        GetCraftingPlanTree second = request(grid, plan, "limit=1&offset=1&captureId=" + token);
        second.runOnServerThread(TestGridFixtures.ae(grid));
        JsonObject remainder = json(
            second.finishResponse()
                .json())
            .getAsJsonObject("data");
        assertEquals(
            token,
            remainder.get("captureId")
                .getAsString());
        assertTrue(
            remainder.get("nextOffset")
                .isJsonNull());
        assertEquals(1, captures.get());
    }

    @Test
    void revokedAccessOrRemovedPlanCannotReleaseCapturedData() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(56);
        AtomicInteger captures = new AtomicInteger();
        GridData data = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid));
        int plan = data.addJob(
            CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(captures)),
            TestGridFixtures.planOutput(),
            new CraftingOptions(false, false),
            false);
        GetCraftingPlanTree denied = request(grid, plan, "");
        denied.runOnServerThread(TestGridFixtures.ae(grid));
        grid.withoutSources();
        assertEquals(
            403,
            denied.finishResponse()
                .httpStatus());
        TestGridFixtures.TestGrid secondGrid = TestGridFixtures.grid(57);
        GridData secondData = GridData.getOrCreate(TestGridFixtures.resolvedKey(secondGrid));
        int secondPlan = secondData.addJob(
            CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(captures)),
            TestGridFixtures.planOutput(),
            new CraftingOptions(false, false),
            false);
        GetCraftingPlanTree removed = request(secondGrid, secondPlan, "");
        removed.runOnServerThread(TestGridFixtures.ae(secondGrid));
        secondData.removeJob(secondPlan);
        assertEquals(
            "INVALID_ID",
            json(
                removed.finishResponse()
                    .json())
                .get("status")
                .getAsString());
    }

    @Test
    void oldCaptureIdIsRejectedBeforeReadingRecycledNodeIds() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(58);
        AtomicInteger captures = new AtomicInteger();
        GridData data = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid));
        int plan = data.addJob(
            CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(captures)),
            TestGridFixtures.planOutput(),
            new CraftingOptions(false, false),
            false);
        GetCraftingPlanTree original = request(grid, plan, "");
        original.runOnServerThread(TestGridFixtures.ae(grid));
        String token = json(
            original.finishResponse()
                .json())
            .getAsJsonObject("data")
            .get("captureId")
            .getAsString();
        GridData.clearRuntimeState();
        int replacement = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(captures)),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        assertEquals(plan, replacement);
        GetCraftingPlanTree stale = request(grid, replacement, "kind=RESOURCE&id=99999&captureId=" + token);
        stale.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            "UPDATE_CHANGED",
            json(
                stale.finishResponse()
                    .json())
                .get("status")
                .getAsString());
        assertEquals(1, captures.get(), "Stale pages must not capture the replacement plan");
    }

    @Test
    void pendingCalculationAndTimeoutNeverBecomePrematureSuccess() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(59);
        GridData data = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid));
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        int plan = data.addJob(pending, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
        GetCraftingPlanTree calculating = request(grid, plan, "");
        calculating.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            "CALCULATING",
            json(
                calculating.finishResponse()
                    .json())
                .getAsJsonObject("data")
                .get("state")
                .getAsString());
        AtomicInteger calls = new AtomicInteger();
        pending.complete(CraftingPlanTreePreparationTest.job(calls));
        GetCraftingPlanTree timedOut = request(grid, plan, "");
        timedOut.failIfPending(ApiStatus.TIMEOUT);
        timedOut.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            "TIMEOUT",
            json(
                timedOut.finishResponse()
                    .json())
                .get("status")
                .getAsString());
        GetCraftingPlanTree next = request(grid, plan, "");
        next.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            "READY",
            json(
                next.finishResponse()
                    .json())
                .getAsJsonObject("data")
                .get("state")
                .getAsString());
        assertEquals(1, calls.get());
    }

    @Test
    void timedOutCaptureRemainsSharedAndCanBeReadByTheNextViewer() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(60);
        AtomicInteger captures = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IAECraftingJob job = CraftingPlanTreePreparationTest.job(() -> {
            captures.incrementAndGet();
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            return CraftingPlanTreePreparationTest.example();
        });
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(job),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        GetCraftingPlanTree first = request(grid, plan, "");
        ExecutorService server = Executors.newSingleThreadExecutor();
        try {
            Future<?> capture = server.submit(() -> first.runOnServerThread(TestGridFixtures.ae(grid)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            first.failIfPending(ApiStatus.TIMEOUT);
            assertEquals(
                "TIMEOUT",
                json(
                    first.finishResponse()
                        .json())
                    .get("status")
                    .getAsString());
            GetCraftingPlanTree concurrent = request(grid, plan, "");
            concurrent.runOnServerThread(TestGridFixtures.ae(grid));
            JsonObject preparing = json(
                concurrent.finishResponse()
                    .json())
                .getAsJsonObject("data");
            assertEquals(
                "PREPARING",
                preparing.get("state")
                    .getAsString());
            release.countDown();
            capture.get(5, TimeUnit.SECONDS);
            GetCraftingPlanTree next = request(grid, plan, "");
            next.runOnServerThread(TestGridFixtures.ae(grid));
            JsonObject ready = json(
                next.finishResponse()
                    .json())
                .getAsJsonObject("data");
            assertEquals(
                "READY",
                ready.get("state")
                    .getAsString());
            assertEquals(preparing.get("captureId"), ready.get("captureId"));
            assertEquals(1, captures.get());
        } finally {
            release.countDown();
            server.shutdownNow();
        }
    }

    @Test
    void unsupportedCaptureIsStableWhileQueueRefusalDoesNotStartIt() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(61);
        AtomicInteger calls = new AtomicInteger();
        IAECraftingJob unsupported = CraftingPlanTreePreparationTest.job(() -> {
            calls.incrementAndGet();
            return null;
        });
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(unsupported),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        GetCraftingPlanTree refused = request(grid, plan, "");
        refused.failIfPending(ApiStatus.SERVER_BUSY);
        assertEquals(
            503,
            refused.finishResponse()
                .httpStatus());
        assertEquals(0, calls.get());
        for (int i = 0; i < 2; i++) {
            GetCraftingPlanTree request = request(grid, plan, "");
            request.runOnServerThread(TestGridFixtures.ae(grid));
            assertEquals(
                "UNAVAILABLE",
                json(
                    request.finishResponse()
                        .json())
                    .getAsJsonObject("data")
                    .get("state")
                    .getAsString());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void recipePagesExposeDeclaredProductsAndPrimaryDesignation() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(62);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(new AtomicInteger())),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        GetCraftingPlanTree request = request(grid, plan, "kind=RECIPE&id=0&limit=1");
        request.runOnServerThread(TestGridFixtures.ae(grid));
        JsonObject data = json(
            request.finishResponse()
                .json())
            .getAsJsonObject("data");
        JsonObject output = data.getAsJsonArray("children")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "RECIPE_OUTPUT",
            output.get("relation")
                .getAsString());
        assertEquals(
            "RESOURCE",
            output.getAsJsonObject("node")
                .get("kind")
                .getAsString());
        assertEquals(
            "3",
            output.get("quantity")
                .getAsString());
        assertTrue(
            output.get("primary")
                .getAsBoolean());
    }

    @Test
    void logoutAndWorldClosureBetweenCaptureAndSerializationFailClosed() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(63);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(new AtomicInteger())),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        AuthSession session = new AuthSession(TestGridFixtures.principal(7), Long.MAX_VALUE);
        AE2Controller.RequestContext context = new AE2Controller.RequestContext(
            new TestGridFixtures.TestExchange(""),
            session);
        Map<String, String> path = new HashMap<>();
        path.put(
            "gridKey",
            TestGridFixtures.resolvedKey(grid)
                .toString());
        path.put("planId", Integer.toString(plan));
        GetCraftingPlanTree loggedOut = new GetCraftingPlanTree();
        assertTrue(loggedOut.init(context.withInputs(context, path, null)));
        loggedOut.runOnServerThread(TestGridFixtures.ae(grid));
        session.invalidate();
        assertEquals(
            401,
            loggedOut.finishResponse()
                .httpStatus());
        GetCraftingPlanTree worldClosed = request(grid, plan, "");
        worldClosed.runOnServerThread(TestGridFixtures.ae(grid));
        CoreEngine.GRID_IDENTITIES.clear();
        assertEquals(
            "GRID_NOT_FOUND",
            json(
                worldClosed.finishResponse()
                    .json())
                .get("status")
                .getAsString());
    }

    @Test
    void pageLimitsAndCaptureTruncationRemainDistinct() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(64);
        int plan = GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(
                CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(new AtomicInteger())),
                TestGridFixtures.planOutput(),
                new CraftingOptions(false, false),
                false);
        GetCraftingPlanTree page = request(grid, plan, "limit=1");
        page.runOnServerThread(TestGridFixtures.ae(grid));
        JsonObject data = json(
            page.finishResponse()
                .json())
            .getAsJsonObject("data");
        assertFalse(
            data.get("partial")
                .getAsBoolean());
        assertFalse(
            data.get("nextOffset")
                .isJsonNull());
        assertTrue(
            data.getAsJsonArray("limits")
                .size() == 0);
        GetCraftingPlanTree invalid = request(grid, plan, "kind=RESOURCE&id=999");
        invalid.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            400,
            invalid.finishResponse()
                .httpStatus());
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
                    TestGridFixtures.principal(7),
                    path,
                    null)));
        return request;
    }

    private static JsonObject json(String value) {
        return new JsonParser().parse(value)
            .getAsJsonObject();
    }
}
