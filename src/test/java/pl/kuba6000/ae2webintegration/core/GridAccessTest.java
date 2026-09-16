package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridAccessTest extends GridTestScope {

    @Test
    void currentSourcesGrantOnlyTheirPlayersAndAdminBypassesSources() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1, 42);
        GridAccess.View view = GridAccess.list(TestGridFixtures.ae(grid))
            .get(0);
        assertTrue(view.allows(TestGridFixtures.principal(42)));
        assertTrue(view.allows(TestGridFixtures.principal(TestGridFixtures.OWNER_ID)));
        assertFalse(view.allows(TestGridFixtures.principal(99)));
        grid.withoutSources();
        assertFalse(view.allows(TestGridFixtures.principal(42)));
        assertTrue(view.allows(WebPrincipal.admin()));
        assertTrue(view.allows(WebPrincipal.localhost()));
        assertFalse(GridAccess.allows(null, WebPrincipal.admin()));
    }

    @Test
    void listingRequiresValidatedUsableIdentity() throws Exception {
        TestGridFixtures.TestGrid online = TestGridFixtures.grid(1);
        TestGridFixtures.TestGrid booting = TestGridFixtures.grid(2)
            .booting();
        TestGridFixtures.TestGrid conflicted = TestGridFixtures.grid(3)
            .controllerState(AEControllerState.CONTROLLER_CONFLICT);
        TestGridFixtures.TestGrid noController = TestGridFixtures.grid(4)
            .noController();
        TestGridFixtures.TestGrid noPathing = TestGridFixtures.grid(5)
            .withoutPathingGrid();
        List<GridAccess.View> views = GridAccess
            .list(TestGridFixtures.ae(online, booting, conflicted, noController, noPathing));
        assertEquals(1, views.size());
        assertSame(
            online,
            views.get(0)
                .grid());
    }

    @Test
    void reverseBindingFollowsRebuiltGridAndRetiresWithLastController() {
        TestGridFixtures.TestGrid oldGrid = TestGridFixtures.grid(1);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(oldGrid);
        assertNotNull(key);
        TestGridFixtures.TestGrid replacement = TestGridFixtures.grid(1);
        assertSame(replacement, CoreEngine.GRID_IDENTITIES.getGrid(key));
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(oldGrid));
        CoreEngine.GRID_IDENTITIES.controllerRemoved(replacement.position());
        assertNull(CoreEngine.GRID_IDENTITIES.getGrid(key));
    }

    @Test
    void retainedSettingsDoNotAuthorizeBeforeGridIsLoadedAgain() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        TestGridFixtures.setTracked(CoreEngine.GRID_IDENTITIES, key, true);
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        assertTrue(TestGridFixtures.isTracked(CoreEngine.GRID_IDENTITIES, key));
        assertNull(CoreEngine.GRID_IDENTITIES.getGrid(key));
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        assertSame(grid, CoreEngine.GRID_IDENTITIES.getGrid(key));
    }
}
