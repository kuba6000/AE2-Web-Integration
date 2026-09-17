package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetGrids;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridListPermissionsTest extends GridTestScope {

    @Test
    void permissionExplanationIsDetachedBeforeHttpReadsTheResponse() throws Exception {
        Thread serverThread = Thread.currentThread();
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(
            1,
            false,
            AEControllerState.CONTROLLER_ONLINE,
            42) {

            @Override
            public @NotNull Map<UUID, List<GridAccessSource>> web$getPermissions() {
                assertSame(serverThread, Thread.currentThread());
                return super.web$getPermissions();
            }

            @Override
            public IAECraftingGrid web$getCraftingGrid() {
                return (IAECraftingGrid) Proxy.newProxyInstance(
                    IAECraftingGrid.class.getClassLoader(),
                    new Class<?>[] { IAECraftingGrid.class },
                    (proxy, method, args) -> {
                        if (method.getName()
                            .equals("web$getCPUCount")) return 0;
                        throw new UnsupportedOperationException(method.getName());
                    });
            }
        };
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        GetGrids request = new GetGrids();
        request.init(TestGridFixtures.context(42, ""));
        request.runOnServerThread(TestGridFixtures.ae(grid));
        grid.web$getPermissions()
            .values()
            .forEach(List::clear);
        grid.withoutSources();
        String json = CompletableFuture.supplyAsync(request::getJSON)
            .get(2, TimeUnit.SECONDS);
        JsonObject response = JsonParser.parseString(json)
            .getAsJsonObject();
        assertEquals(
            "OK",
            response.get("status")
                .getAsString());
        JsonObject sources = response.getAsJsonArray("data")
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("accessSources");
        assertEquals(2, sources.size());
        for (int playerId : new int[] { TestGridFixtures.OWNER_ID, 42 }) {
            PlayerIdentity player = TestGridFixtures.playerIdentity(playerId);
            JsonArray entries = sources.getAsJsonArray(player.uuid.toString());
            assertNotNull(entries);
            assertEquals(1, entries.size());
            JsonObject identity = entries.get(0)
                .getAsJsonObject()
                .getAsJsonObject("player");
            assertEquals(
                player.uuid.toString(),
                identity.get("uuid")
                    .getAsString());
            assertEquals(
                player.name,
                identity.get("name")
                    .getAsString());
        }
        GetGrids next = new GetGrids();
        next.init(TestGridFixtures.context(42, ""));
        next.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            0,
            JsonParser.parseString(next.getJSON())
                .getAsJsonObject()
                .getAsJsonArray("data")
                .size());
    }
}
