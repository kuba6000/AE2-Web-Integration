package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

class ServerLifecycleHttpTest {

    private static final class Response {

        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    @TempDir
    File tempDirectory;

    private IConfigValue<Integer> previousPort;
    private IConfigValue<String> previousPassword;
    private IConfigValue<Boolean> previousLocalAccess;
    private IConfigValue<Boolean> previousPublicMode;
    private File previousConfigDirectory;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        CoreEngine.onServerStopped();
        previousPort = ConfigBootstrap.aePortValue;
        previousPassword = ConfigBootstrap.aePasswordValue;
        previousLocalAccess = ConfigBootstrap.allowNoPasswordOnLocalhostValue;
        previousPublicMode = ConfigBootstrap.aePublicModeValue;
        previousConfigDirectory = Config.getConfigDirectory();

        port = unusedLoopbackPort();
        ConfigBootstrap.aePortValue = () -> port;
        ConfigBootstrap.aePasswordValue = () -> "lifecycle-password";
        ConfigBootstrap.allowNoPasswordOnLocalhostValue = () -> false;
        ConfigBootstrap.aePublicModeValue = () -> false;
        Config.init(tempDirectory);
    }

    @AfterEach
    void tearDown() {
        CoreEngine.onServerStopped();
        ConfigBootstrap.aePortValue = previousPort;
        ConfigBootstrap.aePasswordValue = previousPassword;
        ConfigBootstrap.allowNoPasswordOnLocalhostValue = previousLocalAccess;
        ConfigBootstrap.aePublicModeValue = previousPublicMode;
        if (previousConfigDirectory != null) {
            Config.init(previousConfigDirectory.getParentFile());
        }
    }

    @Test
    void secondServerLifecycleRebindsAndRejectsTheOldWorldToken() throws Exception {
        IAE processInterface = TestGridFixtures.ae();
        CoreData processAccounts = new CoreData();
        AE2Controller.AE2Interface = processInterface;
        CoreData.instance = processAccounts;

        AE2Controller.startHTTPServer();
        String token = login();
        Response firstWorld = performSyncedRequest(token);
        assertEquals(200, firstWorld.status);
        assertTrue(firstWorld.body.contains("\"status\":\"OK\""));

        CoreEngine.onServerStopping();
        CoreEngine.onServerStopped();

        assertSame(processInterface, AE2Controller.AE2Interface);
        assertSame(processAccounts, CoreData.instance);
        AE2Controller.startHTTPServer();

        Response secondWorld = get("/grids", token);
        assertEquals(401, secondWorld.status, "a token issued for the old world must no longer authorize");
    }

    private String login() throws IOException {
        byte[] body = "username=admin&password=lifecycle-password".getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = connection("/auth", null);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        Response response = read(connection);
        assertEquals(200, response.status);
        JsonObject json = new Gson().fromJson(response.body, JsonObject.class);
        return json.get("token")
            .getAsString();
    }

    private Response performSyncedRequest(String token) throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            Future<Response> response = client.submit(() -> get("/grids", token));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (AE2Controller.requests.isEmpty() && !response.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertFalse(AE2Controller.requests.isEmpty(), "the authenticated request should reach the tick queue");
            CoreEngine.onServerTick();
            return response.get(3, TimeUnit.SECONDS);
        } finally {
            client.shutdownNow();
        }
    }

    private Response get(String path, String token) throws IOException {
        return read(connection(path, token));
    }

    private HttpURLConnection connection(String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path)
            .toURL()
            .openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(3_000);
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return new Response(status, "");
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new Response(status, new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static int unusedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
