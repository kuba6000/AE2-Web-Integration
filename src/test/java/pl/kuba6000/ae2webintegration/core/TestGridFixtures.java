package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

/**
 * Shared fakes for grid/authorization tests. Deliberately one copy rather than the per-test-class
 * duplication used elsewhere in this source set - these fakes are used by three test classes.
 */
@SuppressWarnings({ "PMD.AvoidMagicNumbers", "UnstableApiUsage" })
final class TestGridFixtures {

    private TestGridFixtures() {}

    static final int OWNER_ID = 7;

    /** An online grid with a controller source owned by {@link #OWNER_ID}. */
    static TestGrid grid(long securityKey, int... alsoPermitted) {
        TestGrid grid = new TestGrid(securityKey, false, AEControllerState.CONTROLLER_ONLINE, alsoPermitted);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        return grid;
    }

    static StableKey key(long value) {
        return StableKey.create(sink -> sink.putLong(value));
    }

    static TestGrid grid(StableKey key, int... alsoPermitted) {
        TestGrid grid = new TestGrid(key, false, AEControllerState.CONTROLLER_ONLINE, alsoPermitted);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        return grid;
    }

    static void track(IAEGrid grid) {
        try {
            CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
            StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
            assertNotNull(key);
            CoreEngine.GRID_IDENTITIES.setTracked(key, true);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static StableKey resolvedKey(IAEGrid grid) {
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        return CoreEngine.GRID_IDENTITIES.getKey(grid);
    }

    static TestAE ae(IAEGrid... grids) {
        return new TestAE(grids);
    }

    static PlayerIdentity playerIdentity(int playerId) {
        return new PlayerIdentity(UUID.nameUUIDFromBytes(("p" + playerId).getBytes()), "Player" + playerId);
    }

    static WebPrincipal principal(int playerId) {
        if (playerId == -1) {
            return WebPrincipal.admin();
        }
        if (playerId == -2) {
            return WebPrincipal.localhost();
        }
        return WebPrincipal.forPlayer(playerIdentity(playerId));
    }

    static AE2Controller.RequestContext context(int userId, String query) {
        return context(principal(userId), query);
    }

    static AE2Controller.RequestContext context(WebPrincipal principal, String query) {
        return new AE2Controller.RequestContext(new TestExchange(query), principal);
    }

    static class TestGrid implements IAEGrid, IAEPathingGrid {

        private final DimensionalCoords controllerPosition;
        private final List<GridAccessSource> sources = new ArrayList<>();
        private boolean booting;
        private AEControllerState controllerState;
        boolean pathingGridPresent = true;

        TestGrid(long securityKey, boolean booting, AEControllerState state, int... alsoPermitted) {
            this(new DimensionalCoords("world", (int) securityKey, 0, 0), booting, state, alsoPermitted);
        }

        TestGrid(StableKey identity, boolean booting, AEControllerState state, int... alsoPermitted) {
            this(new DimensionalCoords(identity.toString(), 0, 0, 0), booting, state, alsoPermitted);
        }

        TestGrid(DimensionalCoords position, boolean booting, AEControllerState state, int... alsoPermitted) {
            this.controllerPosition = position;
            this.booting = booting;
            this.controllerState = state;
            sources.add(new GridAccessSource(playerIdentity(OWNER_ID), "controller", position(), null, "node_owner"));
            for (int id : alsoPermitted) {
                sources.add(new GridAccessSource(playerIdentity(id), "terminal", position(), null, "node_owner"));
            }
        }

        TestGrid booting() {
            this.booting = true;
            return this;
        }

        TestGrid noController() {
            this.controllerState = AEControllerState.NO_CONTROLLER;
            return this;
        }

        TestGrid controllerState(AEControllerState state) {
            this.controllerState = state;
            return this;
        }

        TestGrid withoutPathingGrid() {
            this.pathingGridPresent = false;
            return this;
        }

        DimensionalCoords position() {
            return controllerPosition;
        }

        TestGrid withoutSources() {
            sources.clear();
            return this;
        }

        @Override
        public @NotNull Set<DimensionalCoords> web$getControllers() {
            return controllerState == AEControllerState.NO_CONTROLLER ? Collections.emptySet()
                : Collections.singleton(position());
        }

        @Override
        public @NotNull List<GridAccessSource> web$getAccessSources() {
            return sources;
        }

        @Override
        public PlayerIdentity web$getRepresentativeOwner() {
            return playerIdentity(OWNER_ID);
        }

        // --- IAEGrid ---
        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return null;
        }

        @Override
        public IAEPathingGrid web$getPathingGrid() {
            return pathingGridPresent ? this : null;
        }

        @Override
        public IAEStorageGrid web$getStorageGrid() {
            return null;
        }

        // --- IAEPathingGrid ---
        @Override
        public boolean web$isNetworkBooting() {
            return booting;
        }

        @Override
        public AEControllerState web$getControllerState() {
            return controllerState;
        }

    }

    static class TestAE implements IAE, IAEPlayerData {

        private final List<IAEGrid> grids;

        TestAE(IAEGrid... grids) {
            this.grids = new ArrayList<>(Arrays.asList(grids));
        }

        @Override
        public Iterable<IAEGrid> web$getGrids() {
            return grids;
        }

        @Override
        public IStackList web$createStackList() {
            return null;
        }

        @Override
        public IAEGenericStack web$stackOf(IAEKey key, long amount) {
            return null;
        }

        @Override
        public IAEPlayerData web$getPlayerData() {
            return this;
        }

        @Override
        public int web$getPlayerId(PlayerIdentity identity) {
            if (identity != null && identity.name.startsWith("Player")) {
                try {
                    return Integer.parseInt(identity.name.substring("Player".length()));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            return -1;
        }
    }

    /** Minimal {@link HttpExchange} carrying only a query string; everything else is unused by tests. */
    static class TestExchange extends HttpExchange {

        private final URI uri;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();

        TestExchange(String query) {
            this.uri = URI.create(query == null || query.isEmpty() ? "/test" : "/test?" + query);
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public String getRequestMethod() {
            return "GET";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return null;
        }

        @Override
        public OutputStream getResponseBody() {
            return null;
        }

        @Override
        public void sendResponseHeaders(int rCode, long responseLength) {}

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public int getResponseCode() {
            return HttpURLConnection.HTTP_OK;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 2324);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {}

        @Override
        public void setStreams(InputStream i, OutputStream o) {}

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
