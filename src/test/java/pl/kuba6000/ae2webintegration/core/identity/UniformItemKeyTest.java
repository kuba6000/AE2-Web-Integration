package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class UniformItemKeyTest {

    @Test
    void streamingChunksPreserveTheCanonicalIdentity() {
        StableKey whole = StableKey.create(sink -> sink.putBytes(new byte[] { 1, 2, 3 }));
        StableKey chunks = StableKey.create(sink -> {
            sink.putByte((byte) 1);
            sink.putBytes(new byte[] { 2, 3 });
        });
        assertEquals("qTcTDu8-ZBplmiM8QEpOSQ", whole.toString());
        assertEquals(whole, chunks);
    }

    @Test
    void eitherHalfOfTheDigestCanDistinguishResources() {
        StableKey zero = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        StableKey firstOnly = StableKey.parse("AAAAAAAAAAEAAAAAAAAAAA");
        StableKey secondOnly = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAQ");
        assertNotEquals(zero, firstOnly);
        assertNotEquals(zero, secondOnly);
        assertNotEquals(firstOnly, secondOnly);
        Map<StableKey, String> resources = new HashMap<>();
        resources.put(zero, "zero");
        resources.put(firstOnly, "first");
        resources.put(secondOnly, "second");
        assertEquals(3, resources.size());
    }

    @Test
    void matchesReferenceMurmurX64VectorsForRawCanonicalBytes() {
        // Python mmh3 5.2.0 reference x64_128_digest, seed 0; expected values are independent literals.
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAA",
            StableKey.create(sink -> {})
                .toString());
        assertEquals(
            "qTcTDu8-ZBplmiM8QEpOSQ",
            StableKey.create(sink -> sink.putBytes(new byte[] { 1, 2, 3 }))
                .toString());
        assertEquals("tO3YysETF1p1PZHh2U6-XQ", StableKey.create(sink -> {
            StableKey.writeText(sink, "item");
            StableKey.writeText(sink, "minecraft:stone");
            sink.putBytes(new byte[] { 0, 0, 0, 0, 0 });
        })
            .toString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = { "", "ik1:qTcTDu8-ZBplmiM8QEpOSQ", "i:minecraft:stone:0", "f:water", "h:9_lvbWH4m1GmC7L8AV0zLA",
            "ik2:9_lvbWH4m1GmC7L8AV0zLA", "9_lvbWH4m1GmC7L8AV0zLA=", "9_lvbWH4m1GmC7L8AV0zLB", "9+lvbWH4m1GmC7L8AV0zLA",
            "9_lvbWH4m1GmC7L8AV0zL", "9_lvbWH4m1GmC7L8AV0zLAA" })
    void rejectsMalformedIdsAndFormerFormats(String value) {
        assertThrows(IllegalArgumentException.class, () -> StableKey.parse(value));
    }

    @Test
    void completeDigestEqualityKeepsBucketCollisionsSeparate() {
        StableKey first = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        StableKey second = StableKey.parse("AAAAAAAAAAEAAAAA____4Q");
        assertNotEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        Map<StableKey, String> resources = new HashMap<>();
        resources.put(first, "first");
        resources.put(second, "second");
        assertEquals("first", resources.get(StableKey.parse(first.toString())));
        assertEquals("second", resources.get(StableKey.parse(second.toString())));
    }

    @Test
    void parsedIdFindsTheSameMapEntryAsTheOriginalIdentity() {
        byte[] identity = new byte[] { 1, 2, 3 };
        StableKey key = StableKey.create(sink -> sink.putBytes(identity));
        Map<StableKey, String> resources = new HashMap<>();
        resources.put(key, "resource");
        assertEquals(
            22,
            key.toString()
                .length());
        assertEquals("resource", resources.get(StableKey.parse(key.toString())));
        identity[0] = 9;
        assertEquals(key, StableKey.create(sink -> sink.putBytes(new byte[] { 1, 2, 3 })));
        assertNotEquals(key, StableKey.create(sink -> sink.putBytes(identity)));
    }
}
