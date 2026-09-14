package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TestGridFixtures.TestGrid;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridAccessSessionsTest extends GridTestScope {

    private static final long T0 = 1_000_000L;
    private static final int OTHER_USER_ID = 99;
    private static final int PERMITTED_USER_ID = 42;
    private static final WebPrincipal OWNER = TestGridFixtures.principal(TestGridFixtures.OWNER_ID);
    private static final WebPrincipal OTHER_USER = TestGridFixtures.principal(OTHER_USER_ID);
    private static final WebPrincipal PERMITTED_USER = TestGridFixtures.principal(PERMITTED_USER_ID);

    @BeforeEach
    void setUp() {
        GridAccessSessions.clear();
    }

    @Test
    void permissionChangesRefreshAccessWithoutScanningControllersOrChangingIdentity() {
        AtomicInteger controllerReads = new AtomicInteger();
        TestGrid grid = new TestGrid(10L, false, AEControllerState.CONTROLLER_ONLINE, PERMITTED_USER_ID) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                controllerReads.incrementAndGet();
                return super.web$getControllers();
            }
        };
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        TestGridFixtures.TestAE ae = TestGridFixtures.ae(grid);
        GridAccessSessions.refresh(ae, OWNER, T0);
        GridAccessSessions.refresh(ae, PERMITTED_USER, T0);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertTrue(
            GridAccessSessions.get(PERMITTED_USER)
                .canAccess(key));
        controllerReads.set(0);

        grid.withoutSources();
        GridAccessSessions.permissionsChanged();

        assertNull(GridAccessSessions.get(OWNER));
        assertNull(GridAccessSessions.get(PERMITTED_USER));
        assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
        GridAccess current = GridAccessSessions.refresh(ae, PERMITTED_USER, T0 + 1);
        assertFalse(current.canAccess(key));
        assertEquals(0, controllerReads.get());
    }

    @Test
    void computeIncludesGridsTheUserOwns() {
        TestGrid grid = TestGridFixtures.grid(10L);
        GridAccess access = GridAccessSessions.compute(TestGridFixtures.ae(grid), OWNER, T0);
        assertTrue(access.canAccess(CoreEngine.GRID_IDENTITIES.getKey(grid)));
    }

    @Test
    void computeIncludesGridsTheUserHasPermissionsOn() {
        TestGrid grid = TestGridFixtures.grid(10L, PERMITTED_USER_ID);
        GridAccess access = GridAccessSessions.compute(TestGridFixtures.ae(grid), PERMITTED_USER, T0);
        assertTrue(access.canAccess(CoreEngine.GRID_IDENTITIES.getKey(grid)));
    }

    @Test
    void computeExcludesGridsTheUserHasNoPermissionsOn() {
        TestGrid grid = TestGridFixtures.grid(10L, PERMITTED_USER_ID);
        GridAccess access = GridAccessSessions.compute(TestGridFixtures.ae(grid), OTHER_USER, T0);
        assertFalse(access.canAccess(CoreEngine.GRID_IDENTITIES.getKey(grid)));
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void computeSeparatesGridsPerUser() {
        TestGrid mine = TestGridFixtures.grid(10L, PERMITTED_USER_ID);
        TestGrid theirs = TestGridFixtures.grid(20L);
        GridAccess access = GridAccessSessions.compute(TestGridFixtures.ae(mine, theirs), PERMITTED_USER, T0);
        assertTrue(access.canAccess(CoreEngine.GRID_IDENTITIES.getKey(mine)));
        assertFalse(
            access.canAccess(CoreEngine.GRID_IDENTITIES.getKey(theirs)),
            "must not see a grid owned by somebody else");
    }

    @Test
    void computeExcludesBootingGrid() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .booting()),
            OWNER,
            T0);
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void computeExcludesGridWithoutOnlineController() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .noController()),
            OWNER,
            T0);
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void computeRequiresPathingService() {
        assertTrue(
            GridAccessSessions.compute(
                TestGridFixtures.ae(
                    TestGridFixtures.grid(10L)
                        .withoutPathingGrid()),
                OWNER,
                T0)
                .getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void computeExcludesGridWithoutAccessSources() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(-1L)
                    .withoutSources()),
            OWNER,
            T0);
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void refreshPopulatesWhenAbsent() {
        assertNull(GridAccessSessions.get(OWNER));
        GridAccessSessions.refresh(TestGridFixtures.ae(TestGridFixtures.grid(10L)), OWNER, T0);
        assertNotNull(GridAccessSessions.get(OWNER));
    }

    @Test
    void unresolvedNativePlayerIdStillAllowsUuidOwnership() {
        TestGrid grid = TestGridFixtures.grid(10L, PERMITTED_USER_ID);
        TestGridFixtures.TestAE ae = new TestGridFixtures.TestAE(grid) {

            @Override
            public int web$getPlayerId(PlayerIdentity identity) {
                return -1;
            }
        };

        GridAccess access = GridAccessSessions.refresh(ae, PERMITTED_USER, T0);

        assertFalse(access.hasResolvedPlayerId());
        assertTrue(access.canAccess(CoreEngine.GRID_IDENTITIES.getKey(grid)));
    }

    @Test
    void aCanonicalNameChangeReusesTheUuidScopedAccessEntry() {
        UUID uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        WebPrincipal oldName = WebPrincipal.forPlayer(new PlayerIdentity(uuid, "OldName"));
        WebPrincipal newName = WebPrincipal.forPlayer(new PlayerIdentity(uuid, "NewName"));
        TestGridFixtures.TestAE ae = new TestGridFixtures.TestAE();

        GridAccessSessions.refresh(ae, oldName, T0);
        GridAccess renamedAccess = GridAccessSessions.refresh(ae, newName, T0 + 1L);

        assertSame(renamedAccess, GridAccessSessions.get(oldName));
        assertSame(renamedAccess, GridAccessSessions.get(newName));
    }

    @Test
    void refreshPicksUpRevokedPermissions() {
        TestGrid grid = TestGridFixtures.grid(10L, PERMITTED_USER_ID);
        TestGridFixtures.TestAE ae = TestGridFixtures.ae(grid);
        GridAccessSessions.refresh(ae, PERMITTED_USER, T0);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertTrue(
            GridAccessSessions.get(PERMITTED_USER)
                .canAccess(key));

        // grid goes offline - the next refresh must drop it
        grid.noController();
        GridAccessSessions.refresh(ae, PERMITTED_USER, T0 + 1L);
        assertFalse(
            GridAccessSessions.get(PERMITTED_USER)
                .canAccess(key));
    }

    @Test
    void anAdminGetsEveryAttachableGridSoTheCheckIsExistenceOnly() {
        TestGrid someoneElses = TestGridFixtures.grid(20L);
        TestGrid mine = TestGridFixtures.grid(10L, PERMITTED_USER_ID);
        GridAccess access = GridAccessSessions
            .compute(TestGridFixtures.ae(mine, someoneElses), WebPrincipal.admin(), T0);

        assertTrue(access.canAccess(CoreEngine.GRID_IDENTITIES.getKey(mine)));
        assertTrue(
            access.canAccess(CoreEngine.GRID_IDENTITIES.getKey(someoneElses)),
            "an admin is not permission-checked");
        assertFalse(access.canAccess(TestGridFixtures.key(999L)), "but a key with no grid behind it is still rejected");
    }

    @Test
    void anAdminStillDoesNotSeeUnusableGrids() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .noController()),
            WebPrincipal.admin(),
            T0);
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void invalidateDropsOnlyThatUser() {
        TestGridFixtures.TestAE ae = TestGridFixtures.ae(TestGridFixtures.grid(10L, PERMITTED_USER_ID));
        GridAccessSessions.refresh(ae, OWNER, T0);
        GridAccessSessions.refresh(ae, PERMITTED_USER, T0);

        GridAccessSessions.invalidate(PERMITTED_USER);

        assertNull(GridAccessSessions.get(PERMITTED_USER));
        assertNotNull(GridAccessSessions.get(OWNER));
    }

    @Test
    void clearDropsEverything() {
        GridAccessSessions.refresh(TestGridFixtures.ae(TestGridFixtures.grid(10L)), OWNER, T0);
        GridAccessSessions.clear();
        assertNull(GridAccessSessions.get(OWNER));
    }
}
