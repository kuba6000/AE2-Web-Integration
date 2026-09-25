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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.GetCPU;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.GetCPUList;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.PauseCPU;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPausableCraftingCPU;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CpuPauseHttpTest extends GridTestScope {

    private HttpServer server;
    private IAE previousAe;
    private String base;
    private final PausableCpu first = new PausableCpu(1);
    private final PausableCpu second = new PausableCpu(2);
    private final TestCpu unsupported = new TestCpu(
        TestGridFixtures.key(3)
            .toString(),
        "Same name",
        128);

    @BeforeEach
    void start() throws Exception {
        first.busy = second.busy = unsupported.busy = true;
        TestGrid grid = new TestGrid(first, second, unsupported);
        base = "/api/grids/" + TestGridFixtures.resolvedKey(grid) + "/cpus";
        previousAe = AE2Controller.AE2Interface;
        AE2Controller.AE2Interface = new TestGridFixtures.TestAE(grid) {

            @Override
            public IStackList web$createStackList() {
                return new IStackList() {

                    public long web$getAmount(IAEKey key) {
                        return 0;
                    }

                    public Iterable<IAEGenericStack> web$stacks() {
                        return Collections.emptyList();
                    }
                };
            }
        };
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ApiRouter router = new ApiRouter(exchange -> {
            String token = exchange.getRequestHeaders()
                .getFirst("Authorization");
            if ("Bearer owner".equals(token) || "authenticationToken=owner".equals(
                exchange.getRequestHeaders()
                    .getFirst("Cookie")))
                return new AE2Controller.RequestContext(
                    exchange,
                    TestGridFixtures.principal(TestGridFixtures.OWNER_ID));
            if ("Bearer other".equals(token))
                return new AE2Controller.RequestContext(exchange, TestGridFixtures.principal(999));
            return null;
        }, exchange -> false, request -> request.runOnServerThread(AE2Controller.AE2Interface));
        router.register(GetCPUList.class);
        router.register(GetCPU.class);
        router.register(PauseCPU.class);
        server.createContext("/api", router);
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        AE2Controller.AE2Interface = previousAe;
    }

    @Test
    void capabilitiesAndExplicitPauseStateRoundTripForTheAddressedCpu() throws Exception {
        JsonObject list = get(base);
        assertTrue(
            list.getAsJsonObject(first.id)
                .get("supportsPause")
                .getAsBoolean());
        assertFalse(
            list.getAsJsonObject(unsupported.id)
                .get("supportsPause")
                .getAsBoolean());
        assertFalse(
            list.getAsJsonObject(unsupported.id)
                .get("isPaused")
                .getAsBoolean());
        assertFalse(
            get(base + "/" + first.id).get("isPaused")
                .getAsBoolean());
        for (int repeat = 0; repeat < 2; repeat++) {
            request("POST", base + "/" + first.id + "/pause", "{\"paused\":true}", "owner", true, 200, "OK");
            assertTrue(
                get(base + "/" + first.id).get("isPaused")
                    .getAsBoolean());
            assertFalse(
                get(base + "/" + second.id).get("isPaused")
                    .getAsBoolean());
        }
        assertTrue(
            get(base).getAsJsonObject(first.id)
                .get("isPaused")
                .getAsBoolean());
        request("POST", base + "/" + first.id + "/pause", "{\"paused\":false}", "owner", true, 200, "OK");
        assertFalse(
            get(base + "/" + first.id).get("isPaused")
                .getAsBoolean());
        assertTrue(
            get(base + "/" + first.id).get("isBusy")
                .getAsBoolean());
    }

    @Test
    void unsupportedIdleAndMissingCpusRejectPauseWithoutChangingOtherCpus() throws Exception {
        request(
            "POST",
            base + "/" + unsupported.id + "/pause",
            "{\"paused\":true}",
            "owner",
            true,
            409,
            "CPU_PAUSE_UNSUPPORTED");
        unsupported.busy = false;
        request(
            "POST",
            base + "/" + unsupported.id + "/pause",
            "{\"paused\":true}",
            "owner",
            true,
            409,
            "CPU_NOT_BUSY");
        first.busy = false;
        first.paused = true;
        JsonObject idle = get(base + "/" + first.id);
        assertTrue(
            idle.get("supportsPause")
                .getAsBoolean());
        assertFalse(
            idle.get("isPaused")
                .getAsBoolean());
        request("POST", base + "/" + first.id + "/pause", "{\"paused\":true}", "owner", true, 409, "CPU_NOT_BUSY");
        request(
            "POST",
            base + "/" + TestGridFixtures.key(99) + "/pause",
            "{\"paused\":true}",
            "owner",
            true,
            404,
            "CPU_NOT_FOUND");
        request(
            "POST",
            "/api/grids/" + TestGridFixtures.key(99) + "/cpus/" + second.id + "/pause",
            "{\"paused\":true}",
            "owner",
            true,
            404,
            "GRID_NOT_FOUND");
        assertFalse(
            get(base + "/" + second.id).get("isPaused")
                .getAsBoolean());
    }

    @Test
    void requiresExplicitBooleanAndAuthorizedMutation() throws Exception {
        String path = base + "/" + first.id + "/pause";
        for (String invalid : new String[] { "", "{}", "{\"paused\":null}", "{\"paused\":\"true\"}", "{\"paused\":1}",
            "{\"paused\":true,\"extra\":1}" }) {
            request("POST", path, invalid, "owner", true, 400, "BAD_PARAM");
        }
        request("GET", path, null, "owner", false, 405, "METHOD_NOT_ALLOWED");
        request("POST", path, "{\"paused\":true}", null, true, 401, "UNAUTHORIZED");
        request("POST", path, "{\"paused\":true}", "other", true, 403, "NO_PERMISSIONS");
        request("POST", path, "{\"paused\":true}", "cookie", false, 403, "CSRF_REJECTED");
        assertFalse(
            get(base + "/" + first.id).get("isPaused")
                .getAsBoolean());
        request("POST", path, "{\"paused\":true}", "cookie", true, 200, "OK");
        assertTrue(
            get(base + "/" + first.id).get("isPaused")
                .getAsBoolean());
    }

    private JsonObject get(String path) throws Exception {
        return request("GET", path, null, "owner", false, 200, "OK").getAsJsonObject("data");
    }

    private JsonObject request(String method, String path, String body, String token, boolean csrf, int expectedCode,
        String expectedStatus) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://localhost:" + server.getAddress()
                .getPort() + path)
            .openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        if ("cookie".equals(token)) connection.setRequestProperty("Cookie", "authenticationToken=owner");
        else if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (csrf) connection.setRequestProperty("X-AE2-Request", "true");
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

        private final LinkedHashSet<ICraftingCPUCluster> cpus;

        TestGrid(ICraftingCPUCluster... cpus) {
            super(222, false, AEControllerState.CONTROLLER_ONLINE);
            this.cpus = new LinkedHashSet<>(Arrays.asList(cpus));
        }

        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return (IAECraftingGrid) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IAECraftingGrid.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("web$getCPUs")) return cpus;
                    throw new AssertionError("Unexpected crafting operation: " + method.getName());
                });
        }
    }

    private static final class PausableCpu extends TestCpu implements IPausableCraftingCPU {

        private boolean paused;

        PausableCpu(long id) {
            super(
                TestGridFixtures.key(id)
                    .toString(),
                "Same name",
                128);
        }

        public boolean web$isPaused() {
            return paused;
        }

        public void web$setPaused(boolean value) {
            paused = value;
        }
    }

    private static class TestCpu implements ICraftingCPUCluster {

        final String id;
        final StableKey key;
        final String name;
        final long storage;
        boolean busy;

        TestCpu(String id, String name, long storage) {
            this.id = id;
            this.key = StableKey.parse(id);
            this.name = name;
            this.storage = storage;
        }

        public @NotNull StableKey web$getKey() {
            return key;
        }

        public String web$getName() {
            return name;
        }

        public long web$getAvailableStorage() {
            return storage;
        }

        public long web$getUsedStorage() {
            return 0;
        }

        public long web$getCoProcessors() {
            return 0;
        }

        public boolean web$isBusy() {
            return busy;
        }

        public void web$cancel() {
            busy = false;
        }

        public IAEGenericStack web$getFinalOutput() {
            return null;
        }

        public long web$getActiveItems(IAEKey key) {
            return 0;
        }

        public long web$getPendingItems(IAEKey key) {
            return 0;
        }

        public long web$getStorageItems(IAEKey key) {
            return 0;
        }

        public void web$getAllItems(IStackList list) {}

        public IStackList web$getWaitingFor() {
            throw new AssertionError("Unexpected waiting inventory");
        }
    }
}
