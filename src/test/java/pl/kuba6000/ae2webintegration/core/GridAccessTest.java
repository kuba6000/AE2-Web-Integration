package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class GridAccessTest {

    private static final long T0 = 1_000_000L;

    private static GridAccess accessTo(long... keys) {
        Set<Long> set = new HashSet<>();
        for (long key : keys) {
            set.add(key);
        }
        return new GridAccess(set, T0);
    }

    @Test
    void canAccessOnlyListedGrids() {
        GridAccess access = accessTo(10L, 20L);
        assertTrue(access.canAccess(10L));
        assertTrue(access.canAccess(20L));
        assertFalse(access.canAccess(30L));
    }

    @Test
    void emptySetGrantsNothing() {
        assertFalse(accessTo().canAccess(10L));
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
    void halfLifeElapsesAtHalfTheTtl() {
        GridAccess access = accessTo(10L);
        assertFalse(access.isHalfLifeElapsed(T0));
        assertFalse(access.isHalfLifeElapsed(T0 + GridAccess.TTL_MILLIS / 2 - 1));
        assertTrue(access.isHalfLifeElapsed(T0 + GridAccess.TTL_MILLIS / 2));
    }

    @Test
    void halfLifeElapsesBeforeStaleness() {
        // The whole point of refreshing at half life: an active session never reaches isStale.
        long midpoint = T0 + GridAccess.TTL_MILLIS / 2;
        GridAccess access = accessTo(10L);
        assertTrue(access.isHalfLifeElapsed(midpoint));
        assertFalse(access.isStale(midpoint));
    }

    @Test
    void keySetIsAnImmutableCopy() {
        Set<Long> source = new HashSet<>();
        source.add(10L);
        GridAccess access = new GridAccess(source, T0);

        source.add(99L); // mutating the source must not leak into the snapshot
        assertFalse(access.canAccess(99L));
        assertEquals(
            1,
            access.getAccessibleGridKeys()
                .size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> access.getAccessibleGridKeys()
                .add(99L));
    }
}
