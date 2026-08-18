package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

class SyncedRequestGridAccessRefreshTest {

    private static final long NEW_GRID_KEY = 10L;
    private static final WebPrincipal LOCALHOST = WebPrincipal.localhost();

    private static final class ProbeRequest extends ISyncedRequest {

        private boolean sawNewGrid;

        @Override
        public void handle(IAE ae) {
            sawNewGrid = GridAccessSessions.get(context.getPrincipal())
                .canAccess(NEW_GRID_KEY);
        }
    }

    @BeforeEach
    void setUp() {
        GridAccessSessions.clear();
    }

    @Test
    void everySyncedRequestPublishesCurrentGridAccessBeforeItsHandlerRuns() {
        GridAccessSessions.put(
            LOCALHOST,
            new GridAccess(GridAccess.UNRESOLVED_PLAYER_ID, Collections.emptySet(), System.currentTimeMillis()));

        ProbeRequest request = new ProbeRequest();
        request.init(TestGridFixtures.context(LOCALHOST, ""));
        request.runOnServerThread(TestGridFixtures.ae(TestGridFixtures.grid(NEW_GRID_KEY)));

        assertTrue(request.sawNewGrid, "a synced request must not observe the previous grid-access snapshot");
    }
}
