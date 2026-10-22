package pl.kuba6000.ae2webintegration.core.auth;

import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.PasswordHelper;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;

/** One login's identity and lifetime, shared by requests and long-lived subscriptions. */
public final class AuthSession {

    public static final long SESSION_MILLIS = TimeUnit.HOURS.toMillis(1);
    public static final long REMEMBERED_MILLIS = TimeUnit.DAYS.toMillis(7);
    public static final long SESSION_CAP_MILLIS = TimeUnit.HOURS.toMillis(12);
    public static final long REMEMBERED_CAP_MILLIS = TimeUnit.DAYS.toMillis(60);
    private static final long TOUCH_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private final WebPrincipal principal;
    private final long absoluteExpiresAtMillis;
    private final boolean persistent;
    private final @Nullable String credential;
    private final @Nullable String credentialProof;
    private volatile long savedExpiry;
    private volatile long expiresAtMillis;
    private volatile boolean invalidated;

    /** Fixed lifetime for non-login identities such as trusted localhost access. */
    public AuthSession(@NotNull WebPrincipal principal, long expiresAtMillis) {
        this(principal, expiresAtMillis, expiresAtMillis, false, null, null);
    }

    AuthSession(WebPrincipal principal, long expiresAtMillis, long absoluteExpiresAtMillis, boolean persistent,
        @Nullable String credential, @Nullable String credentialProof) {
        this.principal = principal;
        this.expiresAtMillis = expiresAtMillis;
        this.absoluteExpiresAtMillis = absoluteExpiresAtMillis;
        this.persistent = persistent;
        this.credential = credential;
        this.credentialProof = credentialProof;
        this.savedExpiry = expiresAtMillis;
    }

    public static @NotNull AuthSession create(@NotNull WebPrincipal principal, boolean persistent, long now,
        @NotNull String credential) {
        try {
            return new AuthSession(
                principal,
                now + (persistent ? REMEMBERED_MILLIS : SESSION_MILLIS),
                now + (persistent ? REMEMBERED_CAP_MILLIS : SESSION_CAP_MILLIS),
                persistent,
                credential,
                persistent ? PasswordHelper.generateStrongPasswordHash(credential) : null);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot protect session credential", failure);
        }
    }

    public @NotNull WebPrincipal principal() {
        return principal;
    }

    public boolean isValid(long nowMillis) {
        return !invalidated && nowMillis < expiresAtMillis;
    }

    /** Genuine client activity extends idle expiry; authorization polling alone does not. */
    public synchronized boolean touch(long now) {
        if (!isValid(now)) return false;
        long extended = Math.min(absoluteExpiresAtMillis, now + (persistent ? REMEMBERED_MILLIS : SESSION_MILLIS));
        if (extended - expiresAtMillis >= TOUCH_INTERVAL_MILLIS || extended == absoluteExpiresAtMillis)
            expiresAtMillis = Math.max(expiresAtMillis, extended);
        return true;
    }

    public synchronized void invalidate() {
        invalidated = true;
    }

    boolean persistent() {
        return persistent;
    }

    long expiresAtMillis() {
        return expiresAtMillis;
    }

    long absoluteExpiresAtMillis() {
        return absoluteExpiresAtMillis;
    }

    @Nullable
    String credential() {
        return credential;
    }

    @Nullable
    String credentialProof() {
        return credentialProof;
    }

    boolean needsSave() {
        return persistent && expiresAtMillis - savedExpiry >= TimeUnit.DAYS.toMillis(1);
    }

    void saved(long expiry) {
        savedExpiry = expiry;
    }
}
