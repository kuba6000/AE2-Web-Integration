package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class PasswordHelperTest {

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 37, 200 })
    void generatesRequestedLengthUsingOnlyAsciiLettersAndDigits(int length) {
        String token = PasswordHelper.generateToken(length);

        assertEquals(length, token.length());
        assertTrue(token.matches("[a-zA-Z0-9]*"));
    }
}
