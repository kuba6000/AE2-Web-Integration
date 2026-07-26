package pl.kuba6000.ae2webintegration.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

/**
 * Shared fakes for grid/authorization tests. Deliberately one copy rather than the per-test-class
 * duplication used elsewhere in this source set - these fakes are used by three test classes.
 */
final class TestGridFixtures {

    private TestGridFixtures() {}

    static final int OWNER_ID = 7;

    /** An online grid with available security, owned by {@link #OWNER_ID}. */
    static TestGrid grid(long securityKey, int... alsoPermitted) {
        return new TestGrid(securityKey, true, false, AEControllerState.CONTROLLER_ONLINE, alsoPermitted);
    }

    static TestAE ae(IAEGrid... grids) {
        return new TestAE(grids);
    }

    static AE2Controller.RequestContext context(int userId, String query) {
        return new AE2Controller.RequestContext(new TestExchange(query), userId);
    }

    static class TestGrid implements IAEGrid, IAESecurityGrid, IAEPathingGrid {

        private final long securityKey;
        private boolean securityAvailable;
        private boolean booting;
        private AEControllerState controllerState;
        private final Set<Integer> permitted = new HashSet<>();
        boolean securityGridPresent = true;
        boolean pathingGridPresent = true;

        TestGrid(long securityKey, boolean securityAvailable, boolean booting, AEControllerState state,
            int... alsoPermitted) {
            this.securityKey = securityKey;
            this.securityAvailable = securityAvailable;
            this.booting = booting;
            this.controllerState = state;
            for (int id : alsoPermitted) {
                permitted.add(id);
            }
        }

        TestGrid securityUnavailable() {
            this.securityAvailable = false;
            return this;
        }

        TestGrid booting() {
            this.booting = true;
            return this;
        }

        TestGrid noController() {
            this.controllerState = AEControllerState.NO_CONTROLLER;
            return this;
        }

        TestGrid withoutSecurityGrid() {
            this.securityGridPresent = false;
            return this;
        }

        TestGrid withoutPathingGrid() {
            this.pathingGridPresent = false;
            return this;
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

        @Override
        public IAESecurityGrid web$getSecurityGrid() {
            return securityGridPresent ? this : null;
        }

        @Override
        public boolean web$isEmpty() {
            return false;
        }

        @Override
        public Object web$getPlayerSource() {
            return null;
        }

        @Override
        public Object web$getLastFakePlayerChatMessage() {
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

        // --- IAESecurityGrid ---
        @Override
        public boolean web$isAvailable() {
            return securityAvailable;
        }

        @Override
        public long web$getSecurityKey() {
            return securityKey;
        }

        @Override
        public int web$getOwner() {
            return OWNER_ID;
        }

        @Override
        public PlayerIdentity web$getOwnerProfile() {
            return new PlayerIdentity(UUID.nameUUIDFromBytes("owner".getBytes()), "Owner");
        }

        @Override
        public boolean web$hasPermissions(int playerId) {
            return playerId == OWNER_ID || permitted.contains(playerId);
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
        public PlayerIdentity web$getPlayerProfile(int playerId) {
            return new PlayerIdentity(UUID.nameUUIDFromBytes(("p" + playerId).getBytes()), "Player" + playerId);
        }

        @Override
        public int web$getPlayerId(PlayerIdentity identity) {
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
            return 200;
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
