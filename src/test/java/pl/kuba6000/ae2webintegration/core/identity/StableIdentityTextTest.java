package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

@SuppressWarnings({ "UnstableApiUsage", "PMD.AvoidMagicNumbers" })
class StableIdentityTextTest {

    @Test
    @SuppressWarnings("UnnecessaryUnicodeEscape") // Keep the exact code points visible in this encoding vector.
    void textUsesByteLengthAndStandardUtf8IncludingNulAndSupplementaryCharacters() {
        Hasher sink = Hashing.sha256()
            .newHasher();
        StableKey.writeText(sink, "\u0000\u00e9\ud83d\ude00");

        assertEquals(
            Hashing.sha256()
                .hashBytes(
                    new byte[] { 0, 0, 0, 7, 0, (byte) 0xc3, (byte) 0xa9, (byte) 0xf0, (byte) 0x9f, (byte) 0x98,
                        (byte) 0x80 }),
            sink.hash());
    }

    @ParameterizedTest
    @ValueSource(strings = { "\ud800", "\udc00", "x\ud800y", "\ud800\ud800" })
    void malformedUnicodeCannotBeReplacedWithAnotherIdentity(String text) {
        Hasher sink = Hashing.sha256()
            .newHasher();
        assertThrows(IllegalArgumentException.class, () -> StableKey.writeText(sink, text));
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sink.hash()
                .toString());
    }
}
