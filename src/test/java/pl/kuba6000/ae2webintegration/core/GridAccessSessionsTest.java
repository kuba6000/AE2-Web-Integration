package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TestGridFixtures.TestGrid;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridAccessSessionsTest {

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
    void computeIncludesGridsTheUserOwns() {
        GridAccess access = GridAccessSessions.compute(TestGridFixtures.ae(TestGridFixtures.grid(10L)), OWNER, T0);
        assertTrue(access.canAccess(10L));
    }

    @Test
    void computeIncludesGridsTheUserHasPermissionsOn() {
        GridAccess access = GridAccessSessions
            .compute(TestGridFixtures.ae(TestGridFixtures.grid(10L, PERMITTED_USER_ID)), PERMITTED_USER, T0);
        assertTrue(access.canAccess(10L));
    }

    @Test
    void computeExcludesGridsTheUserHasNoPermissionsOn() {
        GridAccess access = GridAccessSessions
            .compute(TestGridFixtures.ae(TestGridFixtures.grid(10L, PERMITTED_USER_ID)), OTHER_USER, T0);
        assertFalse(access.canAccess(10L));
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void computeSeparatesGridsPerUser() {
        TestGrid mine = TestGridFixtures.grid(10L, PERMITTED_USER_ID);
        TestGrid theirs = TestGridFixtures.grid(20L);
        GridAccess access = GridAccessSessions.compute(TestGridFixtures.ae(mine, theirs), PERMITTED_USER, T0);
        assertTrue(access.canAccess(10L));
        assertFalse(access.canAccess(20L), "must not see a grid owned by somebody else");
    }

    @Test
    void computeExcludesBootingGrid() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .booting()),
            OWNER,
            T0);
        assertFalse(access.canAccess(10L));
    }

    @Test
    void computeExcludesGridWithoutOnlineController() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .noController()),
            OWNER,
            T0);
        assertFalse(access.canAccess(10L));
    }

    @Test
    void computeExcludesGridWithUnavailableSecurity() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .securityUnavailable()),
            OWNER,
            T0);
        assertFalse(access.canAccess(10L));
    }

    @Test
    void computeExcludesGridWithMissingServices() {
        assertFalse(
            GridAccessSessions.compute(
                TestGridFixtures.ae(
                    TestGridFixtures.grid(10L)
                        .withoutSecurityGrid()),
                OWNER,
                T0)
                .canAccess(10L));
        assertFalse(
            GridAccessSessions.compute(
                TestGridFixtures.ae(
                    TestGridFixtures.grid(10L)
                        .withoutPathingGrid()),
                OWNER,
                T0)
                .canAccess(10L));
    }

    @Test
    void computeExcludesUnattachableGridKey() {
        GridAccess access = GridAccessSessions.compute(TestGridFixtures.ae(TestGridFixtures.grid(-1L)), OWNER, T0);
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
    void unresolvedAePlayerIdFailsClosed() {
        TestGridFixtures.TestAE ae = new TestGridFixtures.TestAE(TestGridFixtures.grid(10L, PERMITTED_USER_ID)) {

            @Override
            public int web$getPlayerId(PlayerIdentity identity) {
                return -1;
            }
        };

        GridAccess access = GridAccessSessions.refresh(ae, PERMITTED_USER, T0);

        assertFalse(access.hasResolvedPlayerId());
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
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
        assertTrue(
            GridAccessSessions.get(PERMITTED_USER)
                .canAccess(10L));

        // grid goes offline - the next refresh must drop it
        grid.noController();
        GridAccessSessions.refresh(ae, PERMITTED_USER, T0 + 1L);
        assertFalse(
            GridAccessSessions.get(PERMITTED_USER)
                .canAccess(10L));
    }

    @Test
    void anAdminGetsEveryAttachableGridSoTheCheckIsExistenceOnly() {
        TestGrid someoneElses = TestGridFixtures.grid(20L);
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(TestGridFixtures.grid(10L, PERMITTED_USER_ID), someoneElses),
            WebPrincipal.admin(),
            T0);

        assertTrue(access.canAccess(10L));
        assertTrue(access.canAccess(20L), "an admin is not permission-checked");
        assertFalse(access.canAccess(999L), "but a key with no grid behind it is still rejected");
    }

    @Test
    void anAdminStillDoesNotSeeUnusableGrids() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .noController()),
            WebPrincipal.admin(),
            T0);
        assertFalse(access.canAccess(10L));
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
