package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.contract.Authentication;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetGridSettings;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.PatchGridSettings;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class ApiRoutingHttpTest extends GridTestScope {

    private HttpServer server;
    private volatile boolean localAccess;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ApiRouter router = new ApiRouter(exchange -> {
            if (localAccess) return new RequestContext(exchange, WebPrincipal.localhost());
            String bearer = exchange.getRequestHeaders()
                .getFirst("Authorization");
            if ("Bearer owner".equals(bearer))
                return new RequestContext(exchange, TestGridFixtures.principal(TestGridFixtures.OWNER_ID));
            if ("Bearer stranger".equals(bearer)) return new RequestContext(exchange, TestGridFixtures.principal(42));
            boolean identified = "Bearer valid".equals(
                exchange.getRequestHeaders()
                    .getFirst("Authorization"))
                || "authenticationToken=valid".equals(
                    exchange.getRequestHeaders()
                        .getFirst("Cookie"));
            return identified ? new RequestContext(exchange, WebPrincipal.admin()) : null;
        }, exchange -> false, ISyncedRequest::handle);
        router.register(Echo.class);
        router.register(Read.class);
        router.register(FailingRead.class);
        router.register(PublicIdentity.class);
        router.register(OptionalIdentity.class);
        router.register(GetGridSettings.class);
        router.register(PatchGridSettings.class);
        server.createContext("/api", router);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Endpoint(method = HttpMethod.PATCH, path = "/api/echo/{name}")
    public static class Echo extends ISyncedRequest {

        @PathParam("name")
        private String name;
        @Body
        private Input input;

        @Desugar
        public record Input(long count, @OptionalInput Boolean enabled) {}

        @Desugar
        public record Output(String name, long count, Boolean enabled) {}

        @Override
        public void handle() {
            succeed(new Output(name, input.count(), input.enabled()));
        }
    }

    @Endpoint(method = HttpMethod.GET, path = "/api/echo/{name}")
    public static class Read extends ISyncedRequest {

        @PathParam("name")
        private String name;

        @Override
        public void handle() {
            succeed(Collections.singletonMap("name", name));
        }
    }

    @Test
    void handlerArgumentFailureIsAnInternalErrorRatherThanInvalidClientInput() throws Exception {
        Reply response = request("GET", "/api/failing", "Authorization: Bearer valid\r\n", "");
        assertEquals(HttpURLConnection.HTTP_INTERNAL_ERROR, response.status());
        assertEquals(
            "INTERNAL_ERROR",
            response.json()
                .get("status")
                .getAsString());
    }

    @Endpoint(method = HttpMethod.GET, path = "/api/failing")
    public static class FailingRead extends IAsyncRequest {

        @Override
        public void handle() {
            throw new IllegalArgumentException("Internal data cannot be processed");
        }
    }

    @Endpoint(method = HttpMethod.POST, path = "/api/public-identity", authentication = Authentication.NONE)
    public static class PublicIdentity extends IAsyncRequest {

        @Override
        public void handle() {
            succeed(
                Collections.singletonMap(
                    "isAdmin",
                    context.getPrincipal()
                        .isAdmin()));
        }
    }

    @Endpoint(method = HttpMethod.POST, path = "/api/optional-identity", authentication = Authentication.OPTIONAL)
    public static class OptionalIdentity extends IAsyncRequest {

        @Override
        public void handle() {
            succeed(
                Collections.singletonMap(
                    "isAdmin",
                    context.getPrincipal()
                        .isAdmin()));
        }
    }

    @Test
    void publicEndpointDoesNotInheritAnExistingAuthenticatedPrincipal() throws Exception {
        for (String credentials : new String[] { "", "Authorization: Bearer valid\r\n",
            "Cookie: authenticationToken=valid\r\n" }) {
            Reply reply = request("POST", "/api/public-identity", credentials, "");
            assertEquals(HttpURLConnection.HTTP_OK, reply.status());
            assertFalse(
                reply.json()
                    .getAsJsonObject("data")
                    .get("isAdmin")
                    .getAsBoolean());
        }
        localAccess = true;
        assertFalse(
            request("POST", "/api/public-identity", "", "").json()
                .getAsJsonObject("data")
                .get("isAdmin")
                .getAsBoolean());
    }

    @Test
    void optionalAuthenticationProtectsAuthenticatedCookieMutations() throws Exception {
        Reply anonymous = request("POST", "/api/optional-identity", "", "");
        assertEquals(HttpURLConnection.HTTP_OK, anonymous.status());
        assertFalse(
            anonymous.json()
                .getAsJsonObject("data")
                .get("isAdmin")
                .getAsBoolean());
        String cookie = "Cookie: authenticationToken=valid\r\n";
        assertEquals(HttpURLConnection.HTTP_FORBIDDEN, request("POST", "/api/optional-identity", cookie, "").status());
        for (String credentials : new String[] { cookie + "X-AE2-Request: true\r\n",
            "Authorization: Bearer valid\r\n" }) {
            Reply identified = request("POST", "/api/optional-identity", credentials, "");
            assertEquals(HttpURLConnection.HTTP_OK, identified.status());
            assertTrue(
                identified.json()
                    .getAsJsonObject("data")
                    .get("isAdmin")
                    .getAsBoolean());
        }
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, request("GET", "/api/echo/example", "", "").status());
        localAccess = true;
        assertEquals(HttpURLConnection.HTTP_FORBIDDEN, request("POST", "/api/optional-identity", "", "").status());
        Reply local = request("POST", "/api/optional-identity", "X-AE2-Request: true\r\n", "");
        assertEquals(HttpURLConnection.HTTP_OK, local.status());
        assertTrue(
            local.json()
                .getAsJsonObject("data")
                .get("isAdmin")
                .getAsBoolean());
    }

    @Test
    void samePathDispatchesByMethodAndBindsStrictJson() throws Exception {
        Reply read = request("GET", "/api/echo/example", "Authorization: Bearer valid\r\n", "");
        assertEquals(HttpURLConnection.HTTP_OK, read.status());
        assertEquals(
            "example",
            read.json()
                .getAsJsonObject("data")
                .get("name")
                .getAsString());
        Reply patch = request(
            "PATCH",
            "/api/echo/example",
            "Authorization: Bearer valid\r\nContent-Type: application/json\r\n",
            "{\"count\":7}");
        assertEquals(HttpURLConnection.HTTP_OK, patch.status());
        assertEquals(
            7,
            patch.json()
                .getAsJsonObject("data")
                .get("count")
                .getAsLong());
        assertTrue(
            patch.json()
                .getAsJsonObject("data")
                .get("enabled")
                .isJsonNull());
        for (String body : new String[] { "{\"count\":\"7\"}", "{\"count\":7,\"enabled\":null}",
            "{\"count\":7,\"other\":0}", "{}" }) {
            assertEquals(
                HttpURLConnection.HTTP_BAD_REQUEST,
                request(
                    "PATCH",
                    "/api/echo/example",
                    "Authorization: Bearer valid\r\nContent-Type: application/json\r\n",
                    body).status());
        }
    }

    @Test
    void rejectsMalformedTransportAndProtectsCookieMutations() throws Exception {
        String bearer = "Authorization: Bearer valid\r\n";
        assertEquals(
            HttpURLConnection.HTTP_UNSUPPORTED_TYPE,
            request("PATCH", "/api/echo/example", bearer + "Content-Type: text/plain\r\n", "{\"count\":7}").status());
        assertEquals(
            HttpURLConnection.HTTP_BAD_REQUEST,
            request("PATCH", "/api/echo/example", bearer + "Content-Type: application/json\r\n", "{count:7}").status());
        assertEquals(
            HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
            request(
                "PATCH",
                "/api/echo/example",
                bearer + "Content-Type: application/json\r\n",
                String.join("", Collections.nCopies(8193, " "))).status());
        String cookie = "Cookie: authenticationToken=valid\r\nContent-Type: application/json\r\n";
        assertEquals(
            HttpURLConnection.HTTP_FORBIDDEN,
            request("PATCH", "/api/echo/example", cookie, "{\"count\":7}").status());
        assertEquals(
            HttpURLConnection.HTTP_OK,
            request("PATCH", "/api/echo/example", cookie + "X-AE2-Request: true\r\n", "{\"count\":7}").status());
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, request("GET", "/api/echo/example/extra", bearer, "").status());
        assertEquals(HttpURLConnection.HTTP_BAD_METHOD, request("DELETE", "/api/echo/example", bearer, "").status());
    }

    @Test
    void networkNameCanBeSavedReadAndClearedWithoutEnablingTracking() throws Exception {
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(TestGridFixtures.grid(1));
        String path = "/api/grids/" + key + "/settings";
        String headers = "Authorization: Bearer valid\r\nContent-Type: application/json\r\n";
        Reply saved = request("PATCH", path, headers, "{\"name\":\"  Factory  \"}");
        assertEquals(HttpURLConnection.HTTP_OK, saved.status());
        assertEquals(
            "Factory",
            saved.json()
                .getAsJsonObject("data")
                .get("name")
                .getAsString());
        assertFalse(
            saved.json()
                .getAsJsonObject("data")
                .get("isTracked")
                .getAsBoolean());
        assertEquals(
            "Factory",
            request("GET", path, headers, "").json()
                .getAsJsonObject("data")
                .get("name")
                .getAsString());
        Reply unchanged = request("PATCH", path, headers, "{\"isTracked\":true}");
        assertEquals(
            "Factory",
            unchanged.json()
                .getAsJsonObject("data")
                .get("name")
                .getAsString());
        Reply cleared = request("PATCH", path, headers, "{\"name\":\"\"}");
        assertEquals(
            "",
            cleared.json()
                .getAsJsonObject("data")
                .get("name")
                .getAsString());
        assertTrue(
            cleared.json()
                .getAsJsonObject("data")
                .get("isTracked")
                .getAsBoolean());
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "null", "true", "123", "[]", "{}", "\"bad\\nname\"", "\"\\tname\"", "\"name\\u0000\"",
            "\"name\\u007f\"", "\"name\\u0085\"", "\"name\\u009f\"" })
    void invalidNameRejectsTheWholeSettingsUpdate(String nameJson) throws Exception {
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(TestGridFixtures.grid(1));
        String path = "/api/grids/" + key + "/settings";
        String headers = "Authorization: Bearer valid\r\nContent-Type: application/json\r\n";
        assertEquals(HttpURLConnection.HTTP_OK, request("PATCH", path, headers, "{\"name\":\"Factory\"}").status());
        Reply invalid = request("PATCH", path, headers, "{\"isTracked\":true,\"name\":" + nameJson + "}");
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, invalid.status());
        JsonObject settings = request("GET", path, headers, "").json()
            .getAsJsonObject("data");
        assertEquals(
            "Factory",
            settings.get("name")
                .getAsString());
        assertFalse(
            settings.get("isTracked")
                .getAsBoolean());
    }

    @Test
    void nameTrimsUnicodeWhitespaceAndLimitsUtf16UnitsBeforeMutating() throws Exception {
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(TestGridFixtures.grid(1));
        String path = "/api/grids/" + key + "/settings";
        String headers = "Authorization: Bearer valid\r\nContent-Type: application/json\r\n";
        String limit = String.join("", Collections.nCopies(64, "\ud83d\ude80"));
        JsonObject input = new JsonObject();
        input.addProperty("name", "\u00a0\u2003\ufeff" + limit + "\u2028\u2029\u3000");
        Reply saved = request("PATCH", path, headers, input.toString());
        assertEquals(HttpURLConnection.HTTP_OK, saved.status());
        assertEquals(
            limit,
            saved.json()
                .getAsJsonObject("data")
                .get("name")
                .getAsString());
        input.addProperty("name", limit + "a");
        input.addProperty("isTracked", true);
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, request("PATCH", path, headers, input.toString()).status());
        JsonObject settings = request("GET", path, headers, "").json()
            .getAsJsonObject("data");
        assertEquals(
            limit,
            settings.get("name")
                .getAsString());
        assertFalse(
            settings.get("isTracked")
                .getAsBoolean());
    }

    @Test
    void namingUsesExistingGridAuthorizationAndCsrfProtection() throws Exception {
        var grid = TestGridFixtures.grid(1);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        String path = "/api/grids/" + key + "/settings";
        String json = "Content-Type: application/json\r\n";
        String body = "{\"name\":\"Factory\",\"isTracked\":true}";
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, request("PATCH", path, json, body).status());
        assertEquals(
            HttpURLConnection.HTTP_FORBIDDEN,
            request("PATCH", path, "Authorization: Bearer stranger\r\n" + json, body).status());
        assertEquals(
            HttpURLConnection.HTTP_FORBIDDEN,
            request("PATCH", path, "Cookie: authenticationToken=valid\r\n" + json, body).status());
        String ownerHeaders = "Authorization: Bearer owner\r\n" + json;
        JsonObject initial = request("GET", path, ownerHeaders, "").json()
            .getAsJsonObject("data");
        assertEquals(
            "",
            initial.get("name")
                .getAsString());
        assertFalse(
            initial.get("isTracked")
                .getAsBoolean());
        assertEquals(HttpURLConnection.HTTP_OK, request("PATCH", path, ownerHeaders, body).status());
        grid.withoutSources();
        assertEquals(
            HttpURLConnection.HTTP_FORBIDDEN,
            request("PATCH", path, ownerHeaders, "{\"name\":\"Changed\"}").status());
        JsonObject current = request("GET", path, "Authorization: Bearer valid\r\n", "").json()
            .getAsJsonObject("data");
        assertEquals(
            "Factory",
            current.get("name")
                .getAsString());
        assertTrue(
            current.get("isTracked")
                .getAsBoolean());
    }

    @Test
    void nameKeepsMongolianVowelSeparatorOnEverySupportedJavaRuntime() throws Exception {
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(TestGridFixtures.grid(1));
        Reply response = request(
            "PATCH",
            "/api/grids/" + key + "/settings",
            "Authorization: Bearer valid\r\nContent-Type: application/json\r\n",
            "{\"name\":\"\u180eFactory\u180e\"}");
        assertEquals(HttpURLConnection.HTTP_OK, response.status());
        assertEquals(
            "\u180eFactory\u180e",
            response.json()
                .getAsJsonObject("data")
                .get("name")
                .getAsString());
    }

    @Test
    void failedNameSaveReturnsErrorAndSameValueRetryPersistsAcrossRestart() throws Exception {
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(TestGridFixtures.grid(1));
        String path = "/api/grids/" + key + "/settings";
        String headers = "Authorization: Bearer valid\r\nContent-Type: application/json\r\n";
        Path file = gridSave.toPath()
            .resolve("ae2webintegration/grid-identities.json");
        Path blocker = file.resolveSibling("grid-identities.json.tmp");
        Files.createDirectory(blocker);
        String body = "{\"name\":\"Factory\"}";
        assertEquals(HttpURLConnection.HTTP_INTERNAL_ERROR, request("PATCH", path, headers, body).status());
        var unsaved = new GridIdentityRegistry(file.toFile()).getPersistentData(key);
        assertNotNull(unsaved);
        assertEquals(
            "",
            unsaved.getSettings()
                .getName());
        Files.deleteIfExists(blocker);
        assertEquals(HttpURLConnection.HTTP_OK, request("PATCH", path, headers, body).status());
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        TestGridFixtures.grid(1);
        JsonObject loaded = request("GET", path, headers, "").json()
            .getAsJsonObject("data");
        assertEquals(
            "Factory",
            loaded.get("name")
                .getAsString());
        assertFalse(
            loaded.get("isTracked")
                .getAsBoolean());
    }

    private Reply request(String method, String path, String headers, String body) throws Exception {
        try (Socket socket = new Socket(
            InetAddress.getLoopbackAddress(),
            server.getAddress()
                .getPort())) {
            socket.setSoTimeout(3000);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(
                (method + " "
                    + path
                    + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + headers
                    + "Content-Length: "
                    + payload.length
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(payload);
            output.flush();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            String response = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            return new Reply(
                Integer.parseInt(response.split(" ", 3)[1]),
                response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }

    @Desugar
    private record Reply(int status, String body) {

        JsonObject json() {
            return new Gson().fromJson(body, JsonObject.class);
        }
    }
}
