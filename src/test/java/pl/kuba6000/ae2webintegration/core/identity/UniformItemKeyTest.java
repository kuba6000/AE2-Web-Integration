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
    void matchesReferenceMurmurX64VectorsIncludingTheProtocolEnvelope() throws Exception {
        // Python mmh3 5.2.0 reference x64_128_digest, seed 0; expected values are independent literals.
        assertEquals(
            "ik1:Uc1y13wJk_4iruKW-iee1g",
            StableItemKey.fromIdentityBytes(new byte[0])
                .toString());
        assertEquals(
            "ik1:9_lvbWH4m1GmC7L8AV0zLA",
            StableItemKey.fromIdentityBytes(new byte[] { 1, 2, 3 })
                .toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        StableItemKey.writeText(output, "item");
        StableItemKey.writeText(output, "minecraft:stone");
        output.writeInt(0);
        output.writeByte(0);
        assertEquals(
            "ik1:10DWn4Y74ZFdUi70c1iPyQ",
            StableItemKey.fromIdentityBytes(bytes.toByteArray())
                .toString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = { "", "ik1:i:minecraft:stone:0", "ik1:f:water", "ik1:h:9_lvbWH4m1GmC7L8AV0zLA",
            "ik2:9_lvbWH4m1GmC7L8AV0zLA", "ik1:9_lvbWH4m1GmC7L8AV0zLA=", "ik1:9_lvbWH4m1GmC7L8AV0zLB",
            "ik1:9+lvbWH4m1GmC7L8AV0zLA", "ik1:9_lvbWH4m1GmC7L8AV0zL", "ik1:9_lvbWH4m1GmC7L8AV0zLAA" })
    void rejectsMalformedIdsAndFormerFormats(String value) {
        assertThrows(IllegalArgumentException.class, () -> StableItemKey.parse(value));
    }

    @Test
    void completeDigestEqualityKeepsBucketCollisionsSeparate() {
        StableItemKey first = StableItemKey.parse("ik1:AAAAAAAAAAAAAAAAAAAAAA");
        StableItemKey second = StableItemKey.parse("ik1:AAAAAAAAAAEAAAAA____4Q");
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
            26,
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
            26,
            key.toString()
                .length());
        assertEquals("resource", resources.get(StableItemKey.parse(key.toString())));
        identity[0] = 9;
        assertEquals(key, StableItemKey.fromIdentityBytes(new byte[] { 1, 2, 3 }));
        assertNotEquals(key, StableItemKey.fromIdentityBytes(identity));
    }
}
