package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CanonicalIdentityOutputTest {

    @Test
    void textUsesByteLengthAndStandardUtf8IncludingNulAndSupplementaryCharacters() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CanonicalIdentityOutput.writeText(new DataOutputStream(bytes), "\u0000\u00e9\ud83d\ude00");

        assertArrayEquals(
            new byte[] { 0, 0, 0, 7, 0, (byte) 0xc3, (byte) 0xa9, (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80 },
            bytes.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(strings = { "\ud800", "\udc00", "x\ud800y", "\ud800\ud800" })
    void malformedUnicodeCannotBeReplacedWithAnotherIdentity(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> CanonicalIdentityOutput.writeText(new DataOutputStream(bytes), text));
        assertEquals(0, bytes.size());
    }

    @Test
    void textSizeIsBoundedByUtf8BytesBeforeWritingAnyOutput() {
        char[] characters = new char[100_000];
        java.util.Arrays.fill(characters, '\u0800');
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThrows(
            IOException.class,
            () -> CanonicalIdentityOutput.writeText(new DataOutputStream(bytes), new String(characters)));
        assertEquals(0, bytes.size());
    }
}
