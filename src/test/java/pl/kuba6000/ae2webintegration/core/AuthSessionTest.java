package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

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
            assertTrue(session.touch(TimeUnit.MINUTES.toMillis(halfHour * 30L)));
        }
        assertFalse(session.touch(TimeUnit.HOURS.toMillis(12)));
        assertFalse(session.isValid(TimeUnit.HOURS.toMillis(12)));
    }

    @Test
    void rememberedSessionsHaveSevenDayIdleWindowAndSixtyDayCap() {
        AuthSession session = AuthSession.create(WebPrincipal.admin(), true, 0, "credential");
        for (int day = 1; day < 60; day++) assertTrue(session.touch(TimeUnit.DAYS.toMillis(day)));
        assertFalse(session.touch(TimeUnit.DAYS.toMillis(60)));
        AuthSession idle = AuthSession.create(WebPrincipal.admin(), true, 0, "credential");
        assertFalse(idle.touch(TimeUnit.DAYS.toMillis(7)));
    }

    @Test
    void staleActivityCannotShortenOrReviveASession() {
        AuthSession session = AuthSession.create(WebPrincipal.admin(), false, 0, "credential");
        assertTrue(session.touch(TimeUnit.MINUTES.toMillis(50)));
        assertTrue(session.touch(TimeUnit.MINUTES.toMillis(10)));
        assertTrue(session.isValid(TimeUnit.MINUTES.toMillis(109)));
        session.invalidate();
        assertFalse(session.touch(TimeUnit.MINUTES.toMillis(55)));
    }
}
