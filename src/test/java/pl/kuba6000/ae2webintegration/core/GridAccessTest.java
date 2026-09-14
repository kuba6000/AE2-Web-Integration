package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridAccessTest {

    private static final long T0 = 1_000_000L;

    private static GridAccess accessTo(long... keys) {
        Set<StableKey> set = new HashSet<>();
        for (long key : keys) {
            set.add(TestGridFixtures.key(key));
        }
        return new GridAccess(42, set, T0);
    }

    @Test
    void canAccessOnlyListedGrids() {
        GridAccess access = accessTo(10L, 20L);
        assertTrue(access.canAccess(TestGridFixtures.key(10L)));
        assertTrue(access.canAccess(TestGridFixtures.key(20L)));
        assertFalse(access.canAccess(TestGridFixtures.key(30L)));
    }

    @Test
    void emptySetGrantsNothing() {
        assertFalse(accessTo().canAccess(TestGridFixtures.key(10L)));
    }

    @Test
    void isNotStaleBeforeTtlElapses() {
        GridAccess access = accessTo(10L);
        assertFalse(access.isStale(T0));
        assertFalse(access.isStale(T0 + GridAccess.TTL_MILLIS - 1));
    }

    @Test
    void isStaleOnceTtlElapses() {
        GridAccess access = accessTo(10L);
        assertTrue(access.isStale(T0 + GridAccess.TTL_MILLIS));
        assertTrue(access.isStale(T0 + GridAccess.TTL_MILLIS * 2));
    }

    @Test
    @SuppressWarnings("DataFlowIssue") // The mutation below must throw to prove the snapshot is immutable.
    void keySetIsAnImmutableCopy() {
        Set<StableKey> source = new HashSet<>();
        source.add(TestGridFixtures.key(10L));
        GridAccess access = new GridAccess(42, source, T0);

        source.add(TestGridFixtures.key(99L)); // mutating the source must not leak into the snapshot
        assertFalse(access.canAccess(TestGridFixtures.key(99L)));
        assertEquals(
            1,
            access.getAccessibleGridKeys()
                .size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> access.getAccessibleGridKeys()
                .add(TestGridFixtures.key(99L)));
    }
}
