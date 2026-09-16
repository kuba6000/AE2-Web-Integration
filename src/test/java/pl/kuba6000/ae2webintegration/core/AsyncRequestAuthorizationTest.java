package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class AsyncRequestAuthorizationTest extends GridTestScope {

    private static final WebPrincipal ME = TestGridFixtures.principal(42);

    private static class ProbeRequest extends IAsyncRequest {

        boolean handlerRan;

        @Override
        public void handle(Map<String, String> params) {
            handlerRan = true;
            done();
        }
    }

    private static ProbeRequest run(WebPrincipal user, String query) {
        ProbeRequest request = new ProbeRequest();
        request.handle(TestGridFixtures.context(user, query));
        return request;
    }

    private static void assertStatus(String status, ProbeRequest request) {
        assertTrue(
            request.getJSON()
                .contains("\"status\":\"" + status + "\""),
            request.getJSON());
        assertEquals(status.equals("OK"), request.handlerRan);
    }

    @Test
    void currentSourceAuthorizesWithoutARequestCacheAndRevocationIsImmediate() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1, 42);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertStatus("OK", run(ME, "grid=" + key));
        grid.withoutSources();
        assertStatus("NO_PERMISSIONS", run(ME, "grid=" + key));
    }

    @Test
    void unrelatedPlayerIsDenied() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        assertStatus("NO_PERMISSIONS", run(ME, "grid=" + CoreEngine.GRID_IDENTITIES.getKey(grid)));
    }

    @Test
    void administrativeAccessStillRequiresALiveRecognizedGrid() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1)
            .withoutSources();
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        for (WebPrincipal user : new WebPrincipal[] { WebPrincipal.admin(), WebPrincipal.localhost() }) {
            assertStatus("OK", run(user, "grid=" + key));
            assertStatus("NO_PERMISSIONS", run(user, "grid=" + TestGridFixtures.key(99)));
        }
    }

    @Test
    void retiringTheIdentityDeniesAccessEvenWhileGridIsReferenced() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1, 42);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        CoreEngine.GRID_IDENTITIES.controllerRemoved(grid.position());
        assertStatus("NO_PERMISSIONS", run(ME, "grid=" + key));
    }

    @Test
    void stoppingTheSaveDeniesAccess() {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1, 42);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        CoreEngine.GRID_IDENTITIES.clear();
        assertStatus("NO_PERMISSIONS", run(ME, "grid=" + key));
    }

    @Test
    void unauthorizedRequestDoesNotCreateGridData() {
        StableKey key = TestGridFixtures.key(99);
        assertStatus("NO_PERMISSIONS", run(ME, "grid=" + key));
        assertNull(GridData.find(key));
    }

    @Test
    void malformedTokenIsRejected() {
        assertStatus("BAD_PARAM", run(ME, "grid=invalid"));
    }

    @Test
    void requestWithoutGridStillReachesHandler() {
        assertStatus("OK", run(ME, ""));
    }

    @Test
    void asyncAuthorizationNeverReadsNativeStateOrFullPermissions() {
        class ThreadBoundGrid extends TestGridFixtures.TestGrid {

            boolean http;

            ThreadBoundGrid() {
                super(1, false, AEControllerState.CONTROLLER_ONLINE, 42);
            }

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                assertFalse(http);
                return super.web$getControllers();
            }

            @Override
            public IAEPathingGrid web$getPathingGrid() {
                assertFalse(http);
                return super.web$getPathingGrid();
            }

            @Override
            public @NotNull Map<UUID, List<GridAccessSource>> web$getPermissions() {
                assertFalse(http);
                return super.web$getPermissions();
            }
        }
        ThreadBoundGrid grid = new ThreadBoundGrid();
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        grid.http = true;
        assertStatus("OK", run(ME, "grid=" + key));
    }
}
