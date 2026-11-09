package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RandomStableKeyTest {

    @Test
    void generatedIdentitiesAreIndependentAndUseTheExistingTokenFormat() {
        StableKey first = StableKey.random();
        StableKey second = StableKey.random();
        assertNotEquals(first, second);
        assertEquals(first, StableKey.parse(first.toString()));
        assertEquals(second, StableKey.parse(second.toString()));
    }
}
