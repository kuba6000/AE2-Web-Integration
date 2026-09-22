package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.http.endpoint.auth.Register;

/** Starts registrations through their public request handler, with the normal game-thread lookup. */
final class RegistrationTestFixture implements AutoCloseable {

    private final ConfigTestFixture config = new ConfigTestFixture();

    RegistrationTestFixture() {
        AE2Controller.stopHTTPServer();
        AuthService.clearWorldState();
        config.set("general.port", ConfigTestFixture.unusedLoopbackPort());
        AE2Controller.startHTTPServer();
    }

    @SuppressWarnings("BusyWait") // Drive game ticks until the asynchronous request completes, bounded by a deadline.
    String begin(PlayerIdentity player, String password) throws Exception {
        IServerPlatform previousPlatform = AE2Controller.serverPlatform;
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public UUID getOnlinePlayerUUID(String username) {
                return player.name.equals(username) ? player.uuid : null;
            }

            @Override
            public ILegacyConfigProvider getLegacyConfig() {
                return null;
            }

            @Override
            public File getConfigDirectory() {
                return null;
            }

            @Override
            public File getWorldDirectory() {
                return null;
            }
        };
        try {
            JsonObject body = new JsonObject();
            body.addProperty("username", player.name);
            body.addProperty("password", password);
            Register request = new Register();
            RequestContext context = new RequestContext(null, WebPrincipal.anonymous(), Collections.emptyMap(), body);
            CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> request.handle(context));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!completion.isDone() && System.nanoTime() < deadline) {
                CoreEngine.onServerTick();
                Thread.sleep(1);
            }
            completion.get(1, TimeUnit.SECONDS);
            JsonObject response = new Gson().fromJson(request.getJSON(), JsonObject.class);
            assertEquals(
                "OK",
                response.get("status")
                    .getAsString());
            return response.getAsJsonObject("data")
                .get("token")
                .getAsString();
        } finally {
            AE2Controller.serverPlatform = previousPlatform;
        }
    }

    @Override
    public void close() {
        AE2Controller.stopHTTPServer();
        AuthService.clearWorldState();
        config.close();
    }
}
