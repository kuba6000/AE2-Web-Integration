package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.auth.AuthSession;

class AuthSessionTest {

    @Test
    void invalidationIsScopedToOneLoginAndExpiryNeedsNoRequest() {
        AuthSession first = new AuthSession(WebPrincipal.admin(), 1000);
        AuthSession second = new AuthSession(WebPrincipal.admin(), 2000);
        assertTrue(first.isValid(999));
        assertFalse(first.isValid(1000));
        assertTrue(second.isValid(1000));
        second.invalidate();
        assertFalse(second.isValid(1000));
    }
}
