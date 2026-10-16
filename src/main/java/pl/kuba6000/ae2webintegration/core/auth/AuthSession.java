package pl.kuba6000.ae2webintegration.core.auth;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;

/** One login's identity and lifetime, shared by requests and long-lived subscriptions. */
public final class AuthSession {

    private final WebPrincipal principal;
    private final long expiresAtMillis;
    private volatile boolean invalidated;

    public AuthSession(@NotNull WebPrincipal principal, long expiresAtMillis) {
        this.principal = principal;
        this.expiresAtMillis = expiresAtMillis;
    }

    public @NotNull WebPrincipal principal() {
        return principal;
    }

    public boolean isValid(long nowMillis) {
        return !invalidated && nowMillis < expiresAtMillis;
    }

    public void invalidate() {
        invalidated = true;
    }
}
