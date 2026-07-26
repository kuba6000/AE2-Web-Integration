package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TestGridFixtures.TestGrid;

class GridAccessSessionsTest {

    private static final long T0 = 1_000_000L;
    private static final int OTHER_USER = 99;
    private static final int PERMITTED_USER = 42;

    @BeforeEach
    void setUp() {
        GridAccessSessions.clear();
    }

    @Test
    void computeIncludesGridsTheUserOwns() {
        GridAccess access = GridAccessSessions
            .compute(TestGridFixtures.ae(TestGridFixtures.grid(10L)), TestGridFixtures.OWNER_ID, false, T0);
        assertTrue(access.canAccess(10L));
    }

    @Test
    void computeIncludesGridsTheUserHasPermissionsOn() {
        GridAccess access = GridAccessSessions
            .compute(TestGridFixtures.ae(TestGridFixtures.grid(10L, PERMITTED_USER)), PERMITTED_USER, false, T0);
        assertTrue(access.canAccess(10L));
    }

    @Test
    void computeExcludesGridsTheUserHasNoPermissionsOn() {
        GridAccess access = GridAccessSessions
            .compute(TestGridFixtures.ae(TestGridFixtures.grid(10L, PERMITTED_USER)), OTHER_USER, false, T0);
        assertFalse(access.canAccess(10L));
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void computeSeparatesGridsPerUser() {
        TestGrid mine = TestGridFixtures.grid(10L, PERMITTED_USER);
        TestGrid theirs = TestGridFixtures.grid(20L);
        GridAccess access = GridAccessSessions.compute(TestGridFixtures.ae(mine, theirs), PERMITTED_USER, false, T0);
        assertTrue(access.canAccess(10L));
        assertFalse(access.canAccess(20L), "must not see a grid owned by somebody else");
    }

    @Test
    void computeExcludesBootingGrid() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .booting()),
            TestGridFixtures.OWNER_ID,
            false,
            T0);
        assertFalse(access.canAccess(10L));
    }

    @Test
    void computeExcludesGridWithoutOnlineController() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .noController()),
            TestGridFixtures.OWNER_ID,
            false,
            T0);
        assertFalse(access.canAccess(10L));
    }

    @Test
    void computeExcludesGridWithUnavailableSecurity() {
        GridAccess access = GridAccessSessions.compute(
            TestGridFixtures.ae(
                TestGridFixtures.grid(10L)
                    .securityUnavailable()),
            TestGridFixtures.OWNER_ID,
            false,
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
                TestGridFixtures.OWNER_ID,
                false,
                T0)
                .canAccess(10L));
        assertFalse(
            GridAccessSessions.compute(
                TestGridFixtures.ae(
                    TestGridFixtures.grid(10L)
                        .withoutPathingGrid()),
                TestGridFixtures.OWNER_ID,
                false,
                T0)
                .canAccess(10L));
    }

    @Test
    void computeExcludesUnattachableGridKey() {
        GridAccess access = GridAccessSessions
            .compute(TestGridFixtures.ae(TestGridFixtures.grid(-1L)), TestGridFixtures.OWNER_ID, false, T0);
        assertTrue(
            access.getAccessibleGridKeys()
                .isEmpty());
    }

    @Test
    void refreshPopulatesWhenAbsent() {
        assertNull(GridAccessSessions.get(TestGridFixtures.OWNER_ID));
        GridAccessSessions.refreshIfHalfLifeElapsed(
            TestGridFixtures.ae(TestGridFixtures.grid(10L)),
            TestGridFixtures.OWNER_ID,
            false,
            T0);
        assertNotNull(GridAccessSessions.get(TestGridFixtures.OWNER_ID));
    }

    @Test
    void refreshIsSkippedBeforeHalfLife() {
        TestGridFixtures.TestAE ae = TestGridFixtures.ae(TestGridFixtures.grid(10L));
        GridAccessSessions.refreshIfHalfLifeElapsed(ae, TestGridFixtures.OWNER_ID, false, T0);
        long firstComputedAt = GridAccessSessions.get(TestGridFixtures.OWNER_ID)
            .getComputedAtMillis();

        GridAccessSessions
            .refreshIfHalfLifeElapsed(ae, TestGridFixtures.OWNER_ID, false, T0 + GridAccess.TTL_MILLIS / 2 - 1);

        assertTrue(
            firstComputedAt == GridAccessSessions.get(TestGridFixtures.OWNER_ID)
                .getComputedAtMillis(),
            "must not recompute before half life");
    }

    @Test
    void refreshRecomputesAfterHalfLife() {
        TestGridFixtures.TestAE ae = TestGridFixtures.ae(TestGridFixtures.grid(10L));
        GridAccessSessions.refreshIfHalfLifeElapsed(ae, TestGridFixtures.OWNER_ID, false, T0);

        long later = T0 + GridAccess.TTL_MILLIS / 2;
        GridAccessSessions.refreshIfHalfLifeElapsed(ae, TestGridFixtures.OWNER_ID, false, later);

        assertTrue(
            later == GridAccessSessions.get(TestGridFixtures.OWNER_ID)
                .getComputedAtMillis());
    }

    @Test
    void refreshPicksUpRevokedPermissions() {
        TestGrid grid = TestGridFixtures.grid(10L, PERMITTED_USER);
        TestGridFixtures.TestAE ae = TestGridFixtures.ae(grid);
        GridAccessSessions.refreshIfHalfLifeElapsed(ae, PERMITTED_USER, false, T0);
        assertTrue(
            GridAccessSessions.get(PERMITTED_USER)
                .canAccess(10L));

        // grid goes offline - the next refresh must drop it
        grid.noController();
        GridAccessSessions.refreshIfHalfLifeElapsed(ae, PERMITTED_USER, false, T0 + GridAccess.TTL_MILLIS);
        assertFalse(
            GridAccessSessions.get(PERMITTED_USER)
                .canAccess(10L));
    }

    @Test
    void anAdminGetsEveryAttachableGridSoTheCheckIsExistenceOnly() {
        TestGrid someoneElses = TestGridFixtures.grid(20L);
        GridAccess access = GridAccessSessions
            .compute(TestGridFixtures.ae(TestGridFixtures.grid(10L, PERMITTED_USER), someoneElses), -1, true, T0);

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
            -1,
            true,
            T0);
        assertFalse(access.canAccess(10L));
    }

    @Test
    void invalidateDropsOnlyThatUser() {
        TestGridFixtures.TestAE ae = TestGridFixtures.ae(TestGridFixtures.grid(10L, PERMITTED_USER));
        GridAccessSessions.refreshIfHalfLifeElapsed(ae, TestGridFixtures.OWNER_ID, false, T0);
        GridAccessSessions.refreshIfHalfLifeElapsed(ae, PERMITTED_USER, false, T0);

        GridAccessSessions.invalidate(PERMITTED_USER);

        assertNull(GridAccessSessions.get(PERMITTED_USER));
        assertNotNull(GridAccessSessions.get(TestGridFixtures.OWNER_ID));
    }

    @Test
    void clearDropsEverything() {
        GridAccessSessions.refreshIfHalfLifeElapsed(
            TestGridFixtures.ae(TestGridFixtures.grid(10L)),
            TestGridFixtures.OWNER_ID,
            false,
            T0);
        GridAccessSessions.clear();
        assertNull(GridAccessSessions.get(TestGridFixtures.OWNER_ID));
    }
}
