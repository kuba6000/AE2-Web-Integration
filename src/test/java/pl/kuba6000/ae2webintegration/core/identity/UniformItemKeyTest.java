package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UniformItemKeyTest {

    @Test
    void eitherHalfOfTheDigestCanDistinguishResources() {
        StableItemKey zero = StableItemKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        StableItemKey firstOnly = StableItemKey.parse("AAAAAAAAAAEAAAAAAAAAAA");
        StableItemKey secondOnly = StableItemKey.parse("AAAAAAAAAAAAAAAAAAAAAQ");
        assertNotEquals(zero, firstOnly);
        assertNotEquals(zero, secondOnly);
        assertNotEquals(firstOnly, secondOnly);
        Map<StableItemKey, String> resources = new HashMap<>();
        resources.put(zero, "zero");
        resources.put(firstOnly, "first");
        resources.put(secondOnly, "second");
        assertEquals(3, resources.size());
    }

    @Test
    void matchesReferenceMurmurX64VectorsForRawCanonicalBytes() throws Exception {
        // Python mmh3 5.2.0 reference x64_128_digest, seed 0; expected values are independent literals.
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAA",
            StableItemKey.fromIdentityBytes(new byte[0])
                .toString());
        assertEquals(
            "qTcTDu8-ZBplmiM8QEpOSQ",
            StableItemKey.fromIdentityBytes(new byte[] { 1, 2, 3 })
                .toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        StableItemKey.writeText(output, "item");
        StableItemKey.writeText(output, "minecraft:stone");
        output.writeInt(0);
        output.writeByte(0);
        assertEquals(
            "tO3YysETF1p1PZHh2U6-XQ",
            StableItemKey.fromIdentityBytes(bytes.toByteArray())
                .toString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = { "", "ik1:qTcTDu8-ZBplmiM8QEpOSQ", "i:minecraft:stone:0", "f:water", "h:9_lvbWH4m1GmC7L8AV0zLA",
            "ik2:9_lvbWH4m1GmC7L8AV0zLA", "9_lvbWH4m1GmC7L8AV0zLA=", "9_lvbWH4m1GmC7L8AV0zLB", "9+lvbWH4m1GmC7L8AV0zLA",
            "9_lvbWH4m1GmC7L8AV0zL", "9_lvbWH4m1GmC7L8AV0zLAA" })
    void rejectsMalformedIdsAndFormerFormats(String value) {
        assertThrows(IllegalArgumentException.class, () -> StableItemKey.parse(value));
    }

    @Test
    void completeDigestEqualityKeepsBucketCollisionsSeparate() {
        StableItemKey first = StableItemKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        StableItemKey second = StableItemKey.parse("AAAAAAAAAAEAAAAA____4Q");
        assertNotEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        Map<StableItemKey, String> resources = new HashMap<>();
        resources.put(first, "first");
        resources.put(second, "second");
        assertEquals("first", resources.get(StableItemKey.parse(first.toString())));
        assertEquals("second", resources.get(StableItemKey.parse(second.toString())));
    }

    @Test
    void rejectsOversizedBodiesInsteadOfHashingATruncatedIdentity() throws Exception {
        assertEquals(
            22,
            StableItemKey.fromIdentityBytes(new byte[256 * 1024])
                .toString()
                .length());
        assertThrows(IdentityLimitException.class, () -> StableItemKey.fromIdentityBytes(new byte[256 * 1024 + 1]));
    }

    @Test
    void parsedIdFindsTheSameMapEntryAsTheOriginalIdentity() throws Exception {
        byte[] identity = new byte[] { 1, 2, 3 };
        StableItemKey key = StableItemKey.fromIdentityBytes(identity);
        Map<StableItemKey, String> resources = new HashMap<>();
        resources.put(key, "resource");
        assertEquals(
            22,
            key.toString()
                .length());
        assertEquals("resource", resources.get(StableItemKey.parse(key.toString())));
        identity[0] = 9;
        assertEquals(key, StableItemKey.fromIdentityBytes(new byte[] { 1, 2, 3 }));
        assertNotEquals(key, StableItemKey.fromIdentityBytes(identity));
    }
}
