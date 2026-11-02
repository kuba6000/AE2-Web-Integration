package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.crafting.CraftingRecipeCapture;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlanTree;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

/**
 * Reads of the whole dependency document through the HTTP router and server-stage lifecycle.
 */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingPlanTreeHttpTest extends GridTestScope {

    private HttpServer server;
    private IAE previousAe;
    private TestGridFixtures.TestGrid grid;
    private final AtomicInteger serverStages = new AtomicInteger();
    private final AtomicBoolean serverThreadStalled = new AtomicBoolean();
    private final AtomicInteger captures = new AtomicInteger();
    private int plan;

    @BeforeEach
    void start() throws Exception {
        grid = TestGridFixtures.grid(310);
        previousAe = AE2Controller.AE2Interface;
        AE2Controller.AE2Interface = TestGridFixtures.ae(grid);
        plan = addPlan(CompletableFuture.completedFuture(CraftingPlanTreePreparationTest.job(() -> {
            captures.incrementAndGet();
            return graph();
        })));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ApiRouter router = new ApiRouter(exchange -> {
            if ("Bearer owner".equals(
                exchange.getRequestHeaders()
                    .getFirst("Authorization"))) {
                return new AE2Controller.RequestContext(
                    exchange,
                    TestGridFixtures.principal(TestGridFixtures.OWNER_ID));
            }
            return null;
        }, exchange -> false, request -> {
            serverStages.incrementAndGet();
            if (!serverThreadStalled.get()) request.runOnServerThread(AE2Controller.AE2Interface);
        });
        router.register(GetCraftingPlanTree.class);
        server.createContext("/api", router);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        AE2Controller.AE2Interface = previousAe;
        GridData.clearRuntimeState();
    }

    @Test
    void capturedGraphIsServedWhileTheServerThreadIsUnavailable() throws Exception {
        JsonObject first = get("", 200, "OK");
        String captureId = data(first).get("captureId")
            .getAsString();
        assertEquals(1, serverStages.get());
        serverThreadStalled.set(true);
        JsonObject next = get("captureId=" + captureId, 200, "OK");
        assertEquals(
            "READY",
            data(next).get("state")
                .getAsString());
        assertEquals(
            captureId,
            data(next).get("captureId")
                .getAsString());
        assertEquals(1, captures.get());
        assertEquals(1, serverStages.get(), "A captured graph must not wait for the server thread");
    }

    @Test
    void wholeDocumentNegotiatesGzipThroughTheHttpRouter() throws Exception {
        URL url = new URL(
            "http://localhost:" + server.getAddress()
                .getPort() + "/api/grids/" + TestGridFixtures.resolvedKey(grid) + "/crafting-plans/" + plan + "/tree");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("Authorization", "Bearer owner");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        int length;
        try {
            assertEquals(200, connection.getResponseCode());
            assertEquals("gzip", connection.getHeaderField("Content-Encoding"));
            assertEquals("Accept-Encoding", connection.getHeaderField("Vary"));
            length = connection.getContentLength();
            try (InputStream input = new GZIPInputStream(connection.getInputStream())) {
                JsonObject response = new JsonParser().parse(IOUtils.toString(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                assertEquals(
                    "READY",
                    data(response).get("state")
                        .getAsString());
                assertTrue(
                    data(response).getAsJsonArray("nodes")
                        .size() > 0);
            }
        } finally {
            connection.disconnect();
        }
        HttpURLConnection head = (HttpURLConnection) url.openConnection();
        head.setRequestMethod("HEAD");
        head.setRequestProperty("Authorization", "Bearer owner");
        head.setRequestProperty("Accept-Encoding", "gzip");
        try {
            assertEquals(200, head.getResponseCode());
            assertEquals("gzip", head.getHeaderField("Content-Encoding"));
            assertEquals(length, head.getContentLength());
            assertEquals(
                -1,
                head.getInputStream()
                    .read());
        } finally {
            head.disconnect();
        }
    }

    @Test
    void firstOpenOfAPlanAndPendingCalculationsStillUseTheServerStage() throws Exception {
        CompletableFuture<IAECraftingJob> pending = new CompletableFuture<>();
        int calculating = addPlan(pending);
        assertEquals(
            "CALCULATING",
            data(get(calculating, "", 200, "OK")).get("state")
                .getAsString());
        assertEquals(
            "CALCULATING",
            data(get(calculating, "", 200, "OK")).get("state")
                .getAsString());
        assertEquals(2, serverStages.get());
        pending.complete(CraftingPlanTreePreparationTest.job(CraftingPlanTreeHttpTest::graph));
        assertEquals(
            "READY",
            data(get(calculating, "", 200, "OK")).get("state")
                .getAsString());
        assertEquals(3, serverStages.get());
        get(calculating, "", 200, "OK");
        assertEquals(3, serverStages.get());
    }

    @Test
    void accessRevokedBetweenRequestsIsRejectedWithoutReleasingCapturedData() throws Exception {
        get("", 200, "OK");
        grid.withoutSources();
        {
            JsonObject denied = get("", 403, "NO_PERMISSIONS");
            assertFalse(
                denied.toString()
                    .contains("captureId"),
                denied.toString());
        }
    }

    @Test
    void removedPlansAndReplacedCapturesAreRejectedOnTheWorkerPath() throws Exception {
        String captureId = data(get("", 200, "OK")).get("captureId")
            .getAsString();
        get("captureId=other", 409, "UPDATE_CHANGED");
        GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .removeJob(plan);
        get("captureId=" + captureId, 404, "INVALID_ID");
        get(plan + 1000, "", 404, "INVALID_ID");
    }

    private int addPlan(CompletableFuture<IAECraftingJob> job) {
        return GridData.getOrCreate(TestGridFixtures.resolvedKey(grid))
            .addJob(job, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);
    }

    private static CraftingRecipeCapture graph() {
        CraftingRecipeCapture.Builder capture = new CraftingRecipeCapture.Builder(100, 10_000);
        for (int i = 0; i < 5; i++) capture.addResource(
            () -> TestGridFixtures.planOutput()
                .description());
        capture.result(0, 9007199254740993L, false);
        int target = capture.addRecipe(1);
        int first = capture.addIngredient(target, 1);
        capture.addCandidate(first, 1, 1);
        capture.addCandidate(first, 2, 1);
        int second = capture.addIngredient(target, 1);
        capture.addCandidate(second, 3, 1);
        capture.addOutput(target, 0, 1, true);
        capture.finishRecipe(target);
        int producerA = capture.addRecipe(1);
        int shared = capture.addIngredient(producerA, 1);
        capture.addCandidate(shared, 4, 1);
        capture.addOutput(producerA, 1, 1, true);
        capture.finishRecipe(producerA);
        int producerB = capture.addRecipe(1);
        int sharedAgain = capture.addIngredient(producerB, 1);
        capture.addCandidate(sharedAgain, 4, 1);
        capture.addOutput(producerB, 2, 1, true);
        capture.finishRecipe(producerB);
        int loop = capture.addRecipe(1);
        int back = capture.addIngredient(loop, 1);
        capture.addCandidate(back, 0, 1);
        capture.addOutput(loop, 4, 1, true);
        capture.finishRecipe(loop);
        return capture.build();
    }

    private static JsonObject data(JsonObject response) {
        return response.getAsJsonObject("data");
    }

    private JsonObject get(String query, int expectedCode, String expectedStatus) throws Exception {
        return get(plan, query, expectedCode, expectedStatus);
    }

    private JsonObject get(int planId, String query, int expectedCode, String expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://localhost:" + server.getAddress()
                .getPort()
                + "/api/grids/"
                + TestGridFixtures.resolvedKey(grid)
                + "/crafting-plans/"
                + planId
                + "/tree"
                + (query.isEmpty() ? "" : "?" + query.replace("|", "%7C")))
            .openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Authorization", "Bearer owner");
        try {
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                JsonObject response = new JsonParser().parse(IOUtils.toString(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
                assertEquals(expectedCode, status, response.toString());
                assertEquals(
                    expectedStatus,
                    response.get("status")
                        .getAsString());
                return response;
            }
        } finally {
            connection.disconnect();
        }
    }
}
