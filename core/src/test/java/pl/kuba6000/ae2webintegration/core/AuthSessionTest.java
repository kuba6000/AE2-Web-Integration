package pl.kuba6000.ae2webintegration.core;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
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

    @Test
    void activityExtendsIdleExpiryButNeverAbsoluteLifetime() {
        AuthSession session = AuthSession.create(WebPrincipal.admin(), false, 0, "credential");
        for (int halfHour = 1; halfHour < 24; halfHour++) {
            assertTrue(session.touch(MINUTES.toMillis(halfHour * 30L)));
        }
        assertFalse(session.touch(HOURS.toMillis(12)));
        assertFalse(session.isValid(HOURS.toMillis(12)));
    }

    @Test
    void rememberedSessionsHaveSevenDayIdleWindowAndSixtyDayCap() {
        AuthSession session = AuthSession.create(WebPrincipal.admin(), true, 0, "credential");
        for (int day = 1; day < 60; day++) assertTrue(session.touch(DAYS.toMillis(day)));
        assertFalse(session.touch(DAYS.toMillis(60)));
        AuthSession idle = AuthSession.create(WebPrincipal.admin(), true, 0, "credential");
        assertFalse(idle.touch(DAYS.toMillis(7)));
    }

    @Test
    void staleActivityCannotShortenOrReviveASession() {
        AuthSession session = AuthSession.create(WebPrincipal.admin(), false, 0, "credential");
        assertTrue(session.touch(MINUTES.toMillis(50)));
        assertTrue(session.touch(MINUTES.toMillis(10)));
        assertTrue(session.isValid(MINUTES.toMillis(109)));
        session.invalidate();
        assertFalse(session.touch(MINUTES.toMillis(55)));
    }
}
