package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class PasswordHelperTest {

    // Password hash vector independently calculated with Python; salt starts with a zero byte.
    private static final String STORED_PASSWORD = "4096:000102030405060708090a0b0c0d0e0f:e77d5a607ac5e7cd52928c3813480258bc3f21ba50aed00dc96a0b24541ce6f0";

    @Test
    void existingPasswordAcceptsMixedCaseHexAndPreservesLeadingZeroBytes() throws Exception {
        assertTrue(PasswordHelper.validatePassword("password", STORED_PASSWORD));
        assertTrue(
            PasswordHelper.validatePassword(
                "password",
                STORED_PASSWORD.replace('a', 'A')
                    .replace('e', 'E')));
        assertFalse(PasswordHelper.validatePassword("wrong password", STORED_PASSWORD));
    }

    @Test
    void generatedPasswordRoundTripsUsingLowercaseHex() throws Exception {
        String stored = PasswordHelper.generateStrongPasswordHash("sample password");
        assertTrue(stored.matches("[0-9]+:[0-9a-f]+:[0-9a-f]+"));
        assertTrue(PasswordHelper.validatePassword("sample password", stored));
        assertFalse(PasswordHelper.validatePassword("different password", stored));
    }

    @Test
    void incompleteHexByteCannotBeSilentlyIgnored() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PasswordHelper.validatePassword("password", STORED_PASSWORD + "a"));
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 37, 200 })
    void generatesRequestedLengthUsingOnlyAsciiLettersAndDigits(int length) {
        String token = PasswordHelper.generateToken(length);

        assertEquals(length, token.length());
        assertTrue(token.matches("[a-zA-Z0-9]*"));
    }
}
