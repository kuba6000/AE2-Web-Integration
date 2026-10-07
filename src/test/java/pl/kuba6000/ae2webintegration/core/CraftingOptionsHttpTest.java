package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.CreateCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.SubmitCraftingPlan;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

class CraftingOptionsHttpTest extends GridTestScope {

    private HttpServer server;
    private IAE previousAe;
    private String base;
    private String itemKey;
    private TestGrid grid;
    private boolean supportsLightMode;

    @BeforeEach
    void start() throws Exception {
        grid = new TestGrid();
        base = "/api/grids/" + TestGridFixtures.resolvedKey(grid) + "/crafting-plans";
        itemKey = AE2Controller.itemIdentities.remember(grid, new ItemIdentityRequestTest.Resource("output", 0, true))
            .toString();
        previousAe = AE2Controller.AE2Interface;
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE(grid) {

            @Override
            public Map<ServerCapability, Boolean> web$getCapabilities() {
                return Collections.singletonMap(ServerCapability.CRAFTING_LIGHT_MODE, supportsLightMode);
            }
        };
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
        }, exchange -> false, request -> request.runOnServerThread(AE2Controller.AE2Interface));
        router.register(CreateCraftingPlan.class);
        router.register(GetCraftingPlan.class);
        router.register(SubmitCraftingPlan.class);
        server.createContext("/api", router);
        server.start();
    }

    @Test
    void advertisedLightModeChangesTheNativePlanAndOmissionPreservesTheNormalPlan() throws Exception {
        supportsLightMode = true;
        JsonObject light = createAndPoll(",\"lightMode\":true");
        assertTrue(
            light.get("isSimulating")
                .getAsBoolean());
        assertEquals(
            64,
            light.getAsJsonArray("plan")
                .get(0)
                .getAsJsonObject()
                .get("missing")
                .getAsLong());
        assertFalse(
            createAndPoll("").get("isSimulating")
                .getAsBoolean());
        assertFalse(
            createAndPoll(",\"lightMode\":false").get("isSimulating")
                .getAsBoolean());
    }

    @Test
    void unsupportedLightModeIsRejectedWhileExplicitlyOffRemainsUsable() throws Exception {
        request("POST", base, "{\"itemKey\":\"" + itemKey + "\",\"quantity\":64,\"lightMode\":true}", 400, "BAD_PARAM");
        assertFalse(
            createAndPoll(",\"lightMode\":false").get("isSimulating")
                .getAsBoolean());
    }

    @Test
    void lightModeRequiresABooleanWhenSupplied() throws Exception {
        supportsLightMode = true;
        for (String invalid : new String[] { "null", "1", "\"true\"", "{}" }) {
            request(
                "POST",
                base,
                "{\"itemKey\":\"" + itemKey + "\",\"quantity\":64,\"lightMode\":" + invalid + "}",
                400,
                "BAD_PARAM");
        }
    }

    private JsonObject createAndPoll(String options) throws Exception {
        JsonObject created = request(
            "POST",
            base,
            "{\"itemKey\":\"" + itemKey + "\",\"quantity\":64" + options + "}",
            202,
            "OK");
        return request(
            "GET",
            base + "/"
                + created.getAsJsonObject("data")
                    .get("jobId")
                    .getAsInt(),
            null,
            200,
            "OK").getAsJsonObject("data");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        AE2Controller.AE2Interface = previousAe;
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void planningWithoutAnAvailableCpuRetainsTheFullPlanAfterRejectedSubmission(boolean hasBusyCpu) throws Exception {
        grid.hasBusyCpu = hasBusyCpu;
        JsonObject created = request("POST", base, "{\"itemKey\":\"" + itemKey + "\",\"quantity\":64}", 202, "OK");
        String planPath = base + "/"
            + created.getAsJsonObject("data")
                .get("jobId")
                .getAsInt();
        JsonObject plan = request("GET", planPath, null, 200, "OK").getAsJsonObject("data");
        assertTrue(
            plan.get("isDone")
                .getAsBoolean());
        assertFalse(
            plan.get("isSimulating")
                .getAsBoolean());
        assertEquals(
            128,
            plan.get("bytesTotal")
                .getAsLong());
        assertEquals(
            64,
            plan.getAsJsonArray("plan")
                .get(0)
                .getAsJsonObject()
                .get("requested")
                .getAsLong());
        request("POST", planPath + "/submit", "{}", 409, "FAIL");
        assertEquals(plan, request("GET", planPath, null, 200, "OK").getAsJsonObject("data"));
    }

    private JsonObject request(String method, String path, String body, int expectedCode, String expectedStatus)
        throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://localhost:" + server.getAddress()
                .getPort() + path)
            .openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Authorization", "Bearer owner");
        connection.setRequestProperty("X-AE2-Request", "true");
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                JsonObject response = new Gson()
                    .fromJson(IOUtils.toString(input, StandardCharsets.UTF_8), JsonObject.class);
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

    private static final class TestGrid extends TestGridFixtures.TestGrid {

        private boolean hasBusyCpu;

        TestGrid() {
            super(222, false, AEControllerState.CONTROLLER_ONLINE);
        }

        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return (IAECraftingGrid) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IAECraftingGrid.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "web$isCurrentlyCraftable":
                            return true;
                        case "web$getCPUs":
                            return hasBusyCpu ? Collections.singleton(busyCpu()) : Collections.emptySet();
                        case "web$beginCraftingJob":
                            return CompletableFuture
                                .completedFuture(new TestJob((Long) args[2], ((CraftingOptions) args[3]).lightMode()));
                        case "web$submitJob":
                            return "No CPU available";
                        default:
                            throw new AssertionError("Unexpected crafting operation: " + method.getName());
                    }
                });
        }

        private ICraftingCPUCluster busyCpu() {
            return (ICraftingCPUCluster) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { ICraftingCPUCluster.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("web$isBusy")) return true;
                    if (method.getName()
                        .equals("web$acceptsPlayerJobs")) return false;
                    throw new AssertionError("Unexpected CPU operation: " + method.getName());
                });
        }

        @Override
        public IAEStorageGrid web$getStorageGrid() {
            return (IAEStorageGrid) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IAEStorageGrid.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("web$getInventory"))
                        return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] { IAEMeInventoryItem.class },
                            (inventory, operation, parameters) -> {
                                throw new AssertionError("Unexpected inventory operation: " + operation.getName());
                            });
                    throw new AssertionError("Unexpected storage operation: " + method.getName());
                });
        }
    }

    private static final class TestJob implements IAECraftingJob {

        private final long quantity;
        private final boolean lightMode;

        TestJob(long quantity, boolean lightMode) {
            this.quantity = quantity;
            this.lightMode = lightMode;
        }

        public boolean web$isSimulation() {
            return lightMode;
        }

        public long web$getByteTotal() {
            return 128;
        }

        public ICraftingPlanSummary web$generateSummary(IAEGrid grid) {
            ICraftingPlanSummaryEntry entry = (ICraftingPlanSummaryEntry) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { ICraftingPlanSummaryEntry.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "web$getWhat":
                            return new ItemIdentityRequestTest.Resource("output", 0, true);
                        case "web$getCraftAmount":
                            return quantity;
                        case "web$getCraftSteps":
                            return 1L;
                        case "web$getStoredAmount":
                            return 0L;
                        case "web$getMissingAmount":
                            return lightMode ? quantity : 0L;
                        default:
                            throw new AssertionError("Unexpected plan operation: " + method.getName());
                    }
                });
            return () -> Collections.singletonList(entry);
        }
    }
}
