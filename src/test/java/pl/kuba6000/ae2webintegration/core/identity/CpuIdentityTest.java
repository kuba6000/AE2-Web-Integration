package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CpuIdentityTest {

    @Test
    void advancedCpuRetainsItsPersistedUuidAndSeparatesFreeCapacity() {
        UUID saved = UUID.fromString("dc8f755e-998b-4b66-975c-036c076cd080");
        StableKey original = CpuIdentity.advanced(saved);
        assertEquals(original, CpuIdentity.advanced(UUID.fromString(saved.toString())));
        assertEquals(original, StableKey.parse(original.toString()));
        assertNotEquals(
            original,
            CpuIdentity.advanced(new UUID(saved.getMostSignificantBits() + 1, saved.getLeastSignificantBits())));
        assertNotEquals(
            original,
            CpuIdentity.advanced(new UUID(saved.getMostSignificantBits(), saved.getLeastSignificantBits() + 1)));

        StableKey free = CpuIdentity.advancedFree("0", 10, 64, -20);
        assertEquals(free, CpuIdentity.advancedFree("0", 10, 64, -20));
        assertEquals(free, StableKey.parse(free.toString()));
        assertNotEquals(free, original);
        assertNotEquals(free, CpuIdentity.ae2("0", 10, 64, -20));
        assertNotEquals(free, CpuIdentity.advancedFree("-1", 10, 64, -20));
        assertNotEquals(free, CpuIdentity.advancedFree("0", 11, 64, -20));
        assertNotEquals(free, CpuIdentity.advancedFree("0", 10, 65, -20));
        assertNotEquals(free, CpuIdentity.advancedFree("0", 10, 64, -21));
    }

    @Test
    void reconstructedCpuHasTheSamePortableKey() {
        StableKey original = CpuIdentity.ae2("minecraft:overworld", -10, 64, 20);
        StableKey restored = CpuIdentity.ae2("minecraft:overworld", -10, 64, 20);
        assertEquals(original, restored);
        assertEquals(original, StableKey.parse(restored.toString()));
        assertEquals(
            22,
            original.toString()
                .length());
        assertNotEquals(original, CpuIdentity.ae2("minecraft:the_nether", -10, 64, 20));
        assertNotEquals(original, CpuIdentity.ae2("minecraft:overworld", -11, 64, 20));
        assertNotEquals(original, CpuIdentity.ae2("minecraft:overworld", -10, 65, 20));
        assertNotEquals(original, CpuIdentity.ae2("minecraft:overworld", -10, 64, 21));
    }
}
