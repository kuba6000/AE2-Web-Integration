package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.CoreEngine.GRID_IDENTITIES;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.http.endpoint.tracking.GetTrackingResources;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

class TrackingResourcesRequestTest extends GridTestScope {

    @Test
    void authorizedPagingAndInvalidRequests() throws Exception {
        try (CraftingHistoryWorld world = new CraftingHistoryWorld(gridSave)) {
            TestGridFixtures.TestGrid grid = TestGridFixtures.grid(41, 42);
            StableKey gridKey = GRID_IDENTITIES.getKey(grid);
            WebPrincipal user = TestGridFixtures.principal(42);
            WebPrincipal stranger = TestGridFixtures.principal(43);
            assertEquals(
                "NO_PERMISSIONS",
                request(stranger, gridKey, "").get("status")
                    .getAsString());
            assertEquals(
                "BAD_PARAM",
                request(user, gridKey, "&after=01").get("status")
                    .getAsString());
            assertEquals(
                "BAD_PARAM",
                request(user, gridKey, "&limit=101").get("status")
                    .getAsString());
            StorageTrackingService service = CoreEngine.getStorageTracking();
            assertNotNull(service);
            StableKey a = StableKey.random();
            StableKey b = StableKey.random();
            Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
            descriptions.put(a, new ResourceDescriptor("test", "alpha", 0, 0, "Alpha", ResourceType.ITEM, a, null));
            descriptions.put(b, new ResourceDescriptor("test", "beta", 0, 0, "Beta", ResourceType.ITEM, b, null));
            service.open(gridKey, 1);
            assertTrue(
                service.submit(
                    gridKey,
                    1,
                    new StorageObservation(
                        System.currentTimeMillis(),
                        true,
                        true,
                        true,
                        Arrays.asList(new StorageObservation.Row(a, 1, false), new StorageObservation.Row(b, 2, false)),
                        Collections.emptyList(),
                        descriptions,
                        Collections.emptyList()),
                    true,
                    false,
                    0));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.resources(gridKey, null, null, 10)
                .entries()
                .size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
            JsonObject page = request(user, gridKey, "&limit=1");
            assertEquals(
                "OK",
                page.get("status")
                    .getAsString());
            assertEquals(
                1,
                page.getAsJsonArray("data")
                    .size());
            String cursor = page.get("nextAfter")
                .getAsString();
            JsonObject next = request(user, gridKey, "&limit=1&after=" + cursor);
            assertEquals(
                1,
                next.getAsJsonArray("data")
                    .size());
            assertNotEquals(
                page.getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject()
                    .get("itemKey"),
                next.getAsJsonArray("data")
                    .get(0)
                    .getAsJsonObject()
                    .get("itemKey"));
            assertTrue(
                !next.has("nextAfter") || next.get("nextAfter")
                    .isJsonNull());
        }
    }

    private static JsonObject request(WebPrincipal user, StableKey grid, String params) {
        GetTrackingResources request = new GetTrackingResources();
        request.handle(TestGridFixtures.context(user, "grid=" + grid + params));
        return new JsonParser().parse(request.getJSON())
            .getAsJsonObject();
    }
}
