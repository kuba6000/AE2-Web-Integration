package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class StableItemKeyTest {

    @Test
    void simpleResourcesHaveReversibleTypeSpecificKeys() {
        SimpleItemIdentity stone = SimpleItemIdentity.item("minecraft:stone", 0);
        SimpleItemIdentity water = SimpleItemIdentity.fluid("water");

        assertEquals(
            "ik1:i:minecraft:stone:0",
            StableItemKey.of(stone)
                .toString());
        assertEquals(
            "ik1:f:water",
            StableItemKey.of(water)
                .toString());
        assertEquals(
            stone,
            StableItemKey.parse("ik1:i:minecraft:stone:0")
                .getSimpleIdentity());
        assertEquals(
            water,
            StableItemKey.parse("ik1:f:water")
                .getSimpleIdentity());
        assertNotEquals(
            StableItemKey.of(SimpleItemIdentity.item("example:water", 0)),
            StableItemKey.of(SimpleItemIdentity.fluid("example:water")));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
        strings = { "", "ik2:i:minecraft:stone:0", "ik1:x:water", "ik1:f:", "ik1:i:stone:0", "ik1:i::stone:0",
            "ik1:i:minecraft::0", "ik1:i:mod:part:stone:0", "ik1:i:minecraft:stone", "ik1:i:minecraft:stone:+0",
            "ik1:i:minecraft:stone:-0", "ik1:i:minecraft:stone:00", "ik1:i:minecraft:stone:01",
            "ik1:i:minecraft:stone: 1", "ik1:i:minecraft:stone:2147483648", "ik1:i:minecraft:stone:-2147483649",
            "ik1:f:wa\nter", "ik1:f:wa\u0085ter", "ik1:f:\uD800" })
    void rejectsMalformedOrNoncanonicalSimpleTokens(String token) {
        assertThrows(IllegalArgumentException.class, () -> StableItemKey.parse(token));
    }

    @Test
    void metadataBoundariesAndUnicodeRegistryTextRoundTripWithoutPlatformLookup() {
        for (int metadata : new int[] { Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE }) {
            SimpleItemIdentity original = SimpleItemIdentity.item("example:variant", metadata);
            StableItemKey key = StableItemKey.of(original);
            assertEquals(
                original,
                StableItemKey.parse(key.toString())
                    .getSimpleIdentity());
            assertEquals(key, StableItemKey.parse(key.toString()));
            assertEquals(
                key.hashCode(),
                StableItemKey.parse(key.toString())
                    .hashCode());
        }
        assertEquals(
            "ik1:f:fluid\uD83D\uDE00",
            StableItemKey.parse("ik1:f:fluid\uD83D\uDE00")
                .toString());
    }

    @Test
    void boundsAcceptedAndGeneratedTokens() {
        char[] characters = new char[506];
        java.util.Arrays.fill(characters, 'a');
        String name = new String(characters);
        assertEquals(
            512,
            StableItemKey.of(SimpleItemIdentity.fluid(name))
                .toString()
                .length());
        assertThrows(IllegalArgumentException.class, () -> StableItemKey.parse("ik1:f:" + name + "a"));
        assertThrows(IllegalArgumentException.class, () -> StableItemKey.of(SimpleItemIdentity.fluid(name + "a")));
        assertThrows(IllegalArgumentException.class, () -> SimpleItemIdentity.item("stone", 0));
        assertThrows(IllegalArgumentException.class, () -> SimpleItemIdentity.fluid("wa\nter"));
    }

    @Test
    void complexIdentityMatchesIndependentProtocolVector() throws IOException {
        StableItemKey key = StableItemKey.complex(output -> {
            CanonicalIdentityOutput.writeText(output, "item");
            CanonicalIdentityOutput.writeText(output, "minecraft:stone");
            output.writeInt(0);
            output.writeByte(0);
        });

        assertEquals("ik1:h:nAU4J-1a_B3zTaf_tREQNg7Cl8VsIvO8VvP9m0jlXM8", key.toString());
        assertEquals(key, StableItemKey.parse(key.toString()));
        assertNull(key.getSimpleIdentity());
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "ik1:h:nAU4J-1a_B3zTaf_tREQNg7Cl8VsIvO8VvP9m0jlXM8=",
            "ik1:h:nAU4J-1a_B3zTaf_tREQNg7Cl8VsIvO8VvP9m0jlXM9", "ik1:h:nAU4J-1a_B3zTaf_tREQNg7Cl8VsIvO8VvP9m0jlXM",
            "ik1:h:nAU4J-1a_B3zTaf_tREQNg7Cl8VsIvO8VvP9m0jlXM8A", "ik1:h:nAU4J+1a_B3zTaf_tREQNg7Cl8VsIvO8VvP9m0jlXM8" })
    void rejectsNoncanonicalComplexEncoding(String token) {
        assertThrows(IllegalArgumentException.class, () -> StableItemKey.parse(token));
    }

    @Test
    void boundsBothBulkAndIncrementalEncodingWithoutReturningTruncatedKeys() throws IOException {
        byte[] atLimit = new byte[256 * 1024];
        assertEquals(
            49,
            StableItemKey.complex(output -> output.write(atLimit))
                .toString()
                .length());
        assertThrows(
            IOException.class,
            () -> StableItemKey.complex(output -> output.write(new byte[atLimit.length + 1])));
        assertThrows(IOException.class, () -> StableItemKey.complex(output -> {
            output.write(atLimit);
            output.writeByte(1);
        }));
        assertThrows(IOException.class, () -> StableItemKey.complex(output -> {
            try {
                output.write(new byte[atLimit.length + 1]);
            } catch (IOException ignored) {
                // A misbehaving native codec must not turn a failed write into a truncated public key.
            }
        }));
    }

    @Test
    void propagatesWriterFailureAndKeepsDigestStateIsolated() throws IOException {
        IOException failure = new IOException("native codec failed");
        assertSame(failure, assertThrows(IOException.class, () -> StableItemKey.complex(output -> {
            output.writeByte(42);
            throw failure;
        })));
        StableItemKey first = StableItemKey.complex(output -> output.writeInt(17));
        assertNotEquals(first, StableItemKey.complex(output -> output.writeInt(18)));
        assertEquals(first, StableItemKey.complex(output -> output.writeInt(17)));
    }
}
