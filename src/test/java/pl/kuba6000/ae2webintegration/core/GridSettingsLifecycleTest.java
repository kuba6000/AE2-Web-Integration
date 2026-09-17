package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetGridSettings;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.PatchGridSettings;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

class GridSettingsLifecycleTest extends GridTestScope {

    private final WebPrincipal owner = TestGridFixtures.principal(TestGridFixtures.OWNER_ID);
    private TestGridFixtures.TestGrid grid;
    private StableKey key;

    @BeforeEach
    void grantGridAccess() {
        grid = TestGridFixtures.grid(1);
        key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
    }

    @ParameterizedTest
    @CsvSource({ "remove,true", "stop,true", "stop,false" })
    void unavailableGridAfterAuthorizationReturnsJson(String event, boolean write) {
        JsonObject response = requestAfter(() -> {
            if (event.equals("remove")) CoreEngine.GRID_IDENTITIES.controllerRemoved(grid.position());
            else CoreEngine.GRID_IDENTITIES.clear();
        }, write);
        assertEquals(
            "GRID_NOT_FOUND",
            response.get("status")
                .getAsString());
    }

    @Test
    void authorizedWriteMayFinishAfterPermissionInvalidation() {
        JsonObject response = requestAfter(grid::withoutSources, true);
        assertEquals(
            "OK",
            response.get("status")
                .getAsString());
        assertTrue(
            response.getAsJsonObject("data")
                .get("isTracked")
                .getAsBoolean());
        assertTrue(TestGridFixtures.isTracked(CoreEngine.GRID_IDENTITIES, key));

        PatchGridSettings next = new PatchGridSettings();
        next.handle(TestGridFixtures.context(owner, "grid=" + key + "&track=0"));
        assertEquals(
            "NO_PERMISSIONS",
            response(next).get("status")
                .getAsString());
        assertTrue(TestGridFixtures.isTracked(CoreEngine.GRID_IDENTITIES, key));
    }

    @Test
    void failedPersistenceReturnsInternalErrorAndRetainsSettingsForRetry() throws Exception {
        Path file = gridSave.toPath()
            .resolve("ae2webintegration/grid-identities.json");
        Path blocker = gridSave.toPath()
            .resolve("ae2webintegration/grid-identities.json.tmp");
        Files.createDirectory(blocker);
        PatchGridSettings request = new PatchGridSettings();
        request.handle(TestGridFixtures.context(owner, "grid=" + key + "&track=1"));
        assertEquals(
            "INTERNAL_ERROR",
            response(request).get("status")
                .getAsString());
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        assertTrue(
            data.getSettings()
                .isTracked());
        assertFalse(TestGridFixtures.isTracked(new GridIdentityRegistry(file.toFile()), key));

        Files.deleteIfExists(blocker);
        PatchGridSettings retry = new PatchGridSettings();
        retry.handle(TestGridFixtures.context(owner, "grid=" + key + "&track=1"));
        assertEquals(
            "OK",
            response(retry).get("status")
                .getAsString());
        assertTrue(TestGridFixtures.isTracked(new GridIdentityRegistry(file.toFile()), key));
    }

    @Test
    void omittedTrackingSettingPreservesItsValue() {
        PatchGridSettings enable = new PatchGridSettings();
        enable.handle(TestGridFixtures.context(owner, "grid=" + key + "&track=1"));
        PatchGridSettings unchanged = new PatchGridSettings();
        unchanged.handle(TestGridFixtures.context(owner, "grid=" + key));
        assertEquals(
            "OK",
            response(unchanged).get("status")
                .getAsString());
        assertTrue(
            response(unchanged).getAsJsonObject("data")
                .get("isTracked")
                .getAsBoolean());
        GetGridSettings read = new GetGridSettings();
        read.handle(TestGridFixtures.context(owner, "grid=" + key));
        assertTrue(
            response(read).getAsJsonObject("data")
                .get("isTracked")
                .getAsBoolean());
    }

    @Test
    void nullTrackingSettingIsRejectedWithoutChangingSettings() {
        PatchGridSettings request = new PatchGridSettings();
        JsonObject body = new JsonObject();
        body.add("isTracked", JsonNull.INSTANCE);
        request.handle(
            new AE2Controller.RequestContext(
                new TestGridFixtures.TestExchange(""),
                owner,
                Collections.singletonMap("gridKey", key.toString()),
                body));
        assertEquals(
            "BAD_PARAM",
            response(request).get("status")
                .getAsString());
        assertEquals(
            HttpURLConnection.HTTP_BAD_REQUEST,
            request.getResponse()
                .httpStatus());
        assertFalse(TestGridFixtures.isTracked(CoreEngine.GRID_IDENTITIES, key));
    }

    private JsonObject requestAfter(Runnable event, boolean write) {
        IAsyncRequest request = write ? new PatchGridSettings() : new GetGridSettings();
        assertTrue(request.init(TestGridFixtures.context(owner, "grid=" + key + (write ? "&track=1" : ""))));
        // Simulate a lifecycle event after authorization, before the endpoint uses the registry.
        event.run();
        request.handle();
        return response(request);
    }

    private static JsonObject response(IRequest request) {
        return GSONUtils.GSON_BUILDER.create()
            .fromJson(request.getJSON(), JsonObject.class);
    }
}
