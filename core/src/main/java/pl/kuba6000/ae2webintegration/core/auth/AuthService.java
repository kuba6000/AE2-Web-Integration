package pl.kuba6000.ae2webintegration.core.auth;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.google.common.net.InetAddresses;
import com.sun.net.httpserver.HttpExchange;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.ClientAddressResolver;
import pl.kuba6000.ae2webintegration.core.PasswordHelper;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.utils.RateLimiter;

/** Shared account authentication and server-global sessions for the API, website and game commands. */
public final class AuthService {

    private static final int RATE_LIMIT_WINDOW_MILLIS = (int) MINUTES.toMillis(1);
    private static final int SESSION_TOKEN_LENGTH = 200;
    private static final int CONFIRMATION_TOKEN_LENGTH = 50;
    private static final Object authenticationStateLock = new Object();
    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static volatile ConcurrentHashMap<String, AuthSession> sessions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingRegistration> registrations = new ConcurrentHashMap<>();
    // Initialized by reloadHttpSettings() before the HTTP listener starts accepting requests.
    private static volatile RateLimiter rateLimiter;
    private static volatile @Nullable RateLimiter credentialRateLimiter;
    private static volatile ClientAddressResolver clientAddressResolver;
    private static volatile @Nullable SessionStore sessionStore;
    private static @Nullable ScheduledExecutorService persistence;
    private static @Nullable String adminCredential;

    private AuthService() {}

    private static volatile AuthSession localSession = new AuthSession(WebPrincipal.localhost(), Long.MAX_VALUE);

    @Desugar
    private record PendingRegistration(@NotNull String token, @NotNull String passwordHash) {}

    /** Token and principal are present exactly when status is OK. */
    @Desugar
    public record LoginResult(@NotNull ApiStatus status, @Nullable String token, @Nullable WebPrincipal principal) {

        private static @NotNull LoginResult failure(@NotNull ApiStatus status) {
            return new LoginResult(status, null, null);
        }
    }

    /** The confirmation token is present exactly when status is OK. */
    @Desugar
    public record RegistrationResult(@NotNull ApiStatus status, @Nullable String token) {}

    public enum ConfirmationResult {
        SUCCESS,
        NOT_PENDING,
        INVALID_TOKEN,
        STORAGE_UNAVAILABLE
    }

    /** Restores remembered logins before accepting requests and applies changed authentication settings. */
    public static synchronized void reloadHttpSettings() {
        SessionStore store = sessionStore;
        if (store == null) {
            store = new SessionStore(Config.getConfigFile("sessions.json"), sessions);
            try {
                store.load(System.currentTimeMillis(), AuthService::credentialFor);
            } catch (IOException failure) {
                LOG.warn("Could not restore remembered sessions", failure);
            }
            sessionStore = store;
            persistence = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "AE2Web-Sessions");
                thread.setDaemon(true);
                return thread;
            });
            SessionStore ownedStore = store;
            Map<String, AuthSession> ownedSessions = sessions;
            persistence.scheduleWithFixedDelay(() -> maintainSessions(ownedStore, ownedSessions), 1, 1, MINUTES);
        }
        if (adminCredential != null && !adminCredential.equals(Config.INSTANCE.general.password)) {
            for (AuthSession session : sessions.values()) if (session.principal()
                .isAdmin()) session.invalidate();
        }
        adminCredential = Config.INSTANCE.general.password;
        maintainSessions(store, sessions);
        if (!Config.INSTANCE.general.allowNoPasswordOnLocalhost) localSession.invalidate();
        else if (!localSession.isValid(System.currentTimeMillis()))
            localSession = new AuthSession(WebPrincipal.localhost(), Long.MAX_VALUE);
        rateLimiter = new RateLimiter(
            Config.INSTANCE.general.maxRequestsBeforeLoggedInPerMinute,
            RATE_LIMIT_WINDOW_MILLIS);
        credentialRateLimiter = new RateLimiter(
            Config.INSTANCE.general.maxRequestsBeforeLoggedInPerMinute,
            RATE_LIMIT_WINDOW_MILLIS);
        clientAddressResolver = ClientAddressResolver.fromConfig(Config.INSTANCE.general.trustedProxies);
    }

    public static boolean isTrustedProxy(@NotNull HttpExchange exchange) {
        return clientAddressResolver.isTrustedProxy(
            exchange.getRemoteAddress()
                .getAddress(),
            exchange.getLocalAddress()
                .getAddress());
    }

    private static @NotNull InetAddress resolveClientAddress(@NotNull HttpExchange exchange) {
        return clientAddressResolver.resolve(
            exchange.getRemoteAddress()
                .getAddress(),
            exchange.getLocalAddress()
                .getAddress(),
            exchange.getRequestHeaders()
                .get("X-Forwarded-For"),
            exchange.getRequestHeaders()
                .get("X-Real-IP"));
    }

    public static boolean isRateLimited(@NotNull HttpExchange exchange) {
        InetAddress client = resolveClientAddress(exchange);
        return !isAlreadyIdentified(exchange, client) && !rateLimiter.isAllowed(client);
    }

    /** Existing sessions do not exempt credential attempts from their shared per-address budget. */
    private static @NotNull ApiStatus admitCredentialAttempt(@NotNull AE2Controller.RequestContext context) {
        RateLimiter limiter = credentialRateLimiter;
        if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration()) || limiter == null) {
            return ApiStatus.SERVER_STOPPING;
        }
        return limiter.isAllowed(resolveClientAddress(context.getExchange())) ? ApiStatus.OK
            : ApiStatus.TOO_MANY_REQUESTS;
    }

    /** Password checks remain behind the limiter; this check only reads existing credentials. */
    private static boolean isAlreadyIdentified(@NotNull HttpExchange exchange, @NotNull InetAddress client) {
        if (isLocalAccess(exchange, client)) return true;
        String token = extractToken(exchange);
        if (token == null) return false;
        AuthSession session = sessions.get(SessionStore.hashToken(token));
        return session != null && isValid(session, System.currentTimeMillis());
    }

    private static boolean isLocalAccess(@NotNull HttpExchange exchange, @NotNull InetAddress client) {
        return !exchange.getRequestHeaders()
            .containsKey("Authorization") && Config.INSTANCE.general.allowNoPasswordOnLocalhost
            && client.isLoopbackAddress()
            && hasMatchingLocalOrigin(exchange);
    }

    /** Loopback transport alone must not grant a foreign webpage implicit administrator access. */
    private static boolean hasMatchingLocalOrigin(@NotNull HttpExchange exchange) {
        List<String> hosts = exchange.getRequestHeaders()
            .get("Host");
        if (hosts == null || hosts.size() != 1) return false;
        String scheme = "http";
        List<String> forwarded = exchange.getRequestHeaders()
            .get("X-Forwarded-Proto");
        if (forwarded != null && isTrustedProxy(exchange)) {
            if (forwarded.size() != 1) return false;
            scheme = forwarded.get(0)
                .trim();
        }
        URI target = parseHttpOrigin(scheme + "://" + hosts.get(0));
        if (target == null) return false;
        String host = target.getHost()
            .toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        else if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        // RFC 6761 reserves localhost and its subdomains; address parsing must not perform DNS lookups.
        if (!host.equals("localhost") && !host.endsWith(".localhost")
            && !(InetAddresses.isInetAddress(host) && InetAddresses.forString(host)
                .isLoopbackAddress()))
            return false;
        List<String> origins = exchange.getRequestHeaders()
            .get("Origin");
        if (origins == null) return true; // Navigations, same-origin GETs and non-browser clients may omit Origin.
        if (origins.size() != 1) return false;
        URI source = parseHttpOrigin(origins.get(0));
        return source != null && target.getScheme()
            .equalsIgnoreCase(source.getScheme())
            && target.getHost()
                .equalsIgnoreCase(source.getHost())
            && effectivePort(target) == effectivePort(source);
    }

    private static @Nullable URI parseHttpOrigin(@NotNull String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || !uri.getRawPath()
                    .isEmpty()
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() > 65535
                || value.endsWith(":")) return null;
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static int effectivePort(@NotNull URI origin) {
        return origin.getPort() < 0 ? ("https".equalsIgnoreCase(origin.getScheme()) ? 443 : 80) : origin.getPort();
    }

    /** An explicit Authorization header always takes precedence over browser cookies. */
    public static @Nullable String extractToken(@NotNull HttpExchange exchange) {
        List<String> authorization = exchange.getRequestHeaders()
            .get("Authorization");
        if (authorization != null) {
            if (authorization.size() != 1) return null;
            String value = authorization.get(0);
            if (!value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) return null;
            String token = value.substring("Bearer ".length())
                .trim();
            return token.isEmpty() ? null : token;
        }
        List<String> cookies = exchange.getRequestHeaders()
            .get("Cookie");
        if (cookies != null) {
            for (String header : cookies) {
                for (String part : header.split(";")) {
                    String cookie = part.trim();
                    if (cookie.startsWith("authenticationToken=")) {
                        return cookie.substring("authenticationToken=".length());
                    }
                }
            }
        }
        return null;
    }

    public static @Nullable AE2Controller.RequestContext authenticate(@NotNull HttpExchange exchange) {
        if (isLocalAccess(exchange, resolveClientAddress(exchange))) {
            return new AE2Controller.RequestContext(exchange, localSession);
        }
        String token = extractToken(exchange);
        if (token == null) return null;
        String hash = SessionStore.hashToken(token);
        AuthSession session = sessions.get(hash);
        if (session == null) return null;
        long now = System.currentTimeMillis();
        if (!isValid(session, now) || !session.touch(now)) {
            if (sessions.remove(hash, session) && session.persistent()) {
                SessionStore store = sessionStore;
                if (store != null) store.markChanged(true);
            }
            return null;
        }
        return new AE2Controller.RequestContext(exchange, session);
    }

    /** Verifies credentials and publishes a session only into the originating HTTP lifecycle. */
    public static @NotNull LoginResult login(@NotNull AE2Controller.RequestContext context, @NotNull String username,
        @NotNull String password, boolean rememberMe) {
        ApiStatus admission = admitCredentialAttempt(context);
        if (admission != ApiStatus.OK) return LoginResult.failure(admission);
        WebPrincipal principal;
        String credential;
        if (username.equalsIgnoreCase("admin") || !Config.INSTANCE.general.publicMode) {
            credential = Config.INSTANCE.general.password;
            if (!password.equals(credential) && !credential.isEmpty()) {
                return LoginResult.failure(ApiStatus.INVALID_PASSWORD);
            }
            principal = WebPrincipal.admin();
        } else {
            CoreData.Account account = CoreData.getAccount(username);
            if (account == null) return LoginResult.failure(ApiStatus.INVALID_USER);
            if (!CoreData.verifyPassword(account, password)) return LoginResult.failure(ApiStatus.INVALID_PASSWORD);
            principal = WebPrincipal.forPlayer(account.getIdentity());
            credential = account.getCredentialVersion();
        }
        String token = PasswordHelper.generateToken(SESSION_TOKEN_LENGTH);
        AuthSession session = AuthSession.create(principal, rememberMe, System.currentTimeMillis(), credential);
        SessionStore store;
        boolean evicted;
        synchronized (authenticationStateLock) {
            if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration()))
                return LoginResult.failure(ApiStatus.SERVER_STOPPING);
            if (!credential.equals(credentialFor(principal))) return LoginResult.failure(ApiStatus.INVALID_PASSWORD);
            evicted = rememberMe && evictOldestRemembered(principal);
            sessions.put(SessionStore.hashToken(token), session);
            store = sessionStore;
        }
        if (rememberMe && store != null) {
            if (evicted) {
                try {
                    store.saveRevocation(System.currentTimeMillis());
                } catch (IOException failure) {
                    session.invalidate();
                    sessions.remove(SessionStore.hashToken(token), session);
                    LOG.warn("Could not persist remembered-session eviction", failure);
                    return LoginResult.failure(ApiStatus.INTERNAL_ERROR);
                }
            } else saveSessions(store);
        }
        return new LoginResult(ApiStatus.OK, token, principal);
    }

    /** Called inside the admission lock; fixed absolute lifetimes also order remembered logins by age. */
    private static boolean evictOldestRemembered(@NotNull WebPrincipal principal) {
        List<Map.Entry<String, AuthSession>> remembered = new ArrayList<>();
        for (Map.Entry<String, AuthSession> entry : sessions.entrySet()) {
            AuthSession existing = entry.getValue();
            if (existing.persistent() && existing.principal()
                .equals(principal)) remembered.add(entry);
        }
        int excess = remembered.size() - AuthSession.MAX_REMEMBERED_PER_ACCOUNT + 1;
        if (excess <= 0) return false;
        remembered.sort(
            Comparator.comparingLong(
                entry -> entry.getValue()
                    .absoluteExpiresAtMillis()));
        for (int i = 0; i < excess; i++) {
            Map.Entry<String, AuthSession> oldest = remembered.get(i);
            oldest.getValue()
                .invalidate();
            sessions.remove(oldest.getKey(), oldest.getValue());
        }
        return true;
    }

    /** The online-player lookup runs on the game thread; password hashing runs on the caller's HTTP thread. */
    public static @NotNull RegistrationResult register(@NotNull AE2Controller.RequestContext context,
        @NotNull String username, @NotNull String password) {
        ApiStatus admission = admitCredentialAttempt(context);
        if (admission != ApiStatus.OK) return new RegistrationResult(admission, null);
        UUID uuid;
        try {
            uuid = AE2Controller.findOnlinePlayerOnServerThread(username);
        } catch (AE2Controller.ServerTaskUnavailableException exception) {
            return new RegistrationResult(exception.getStatus(), null);
        }
        if (uuid == null) return new RegistrationResult(ApiStatus.NOT_ONLINE, null);
        String passwordHash;
        try {
            passwordHash = PasswordHelper.generateStrongPasswordHash(password);
        } catch (Exception exception) {
            return new RegistrationResult(ApiStatus.INVALID_PASSWORD, null);
        }
        String token = PasswordHelper.generateToken(CONFIRMATION_TOKEN_LENGTH);
        synchronized (authenticationStateLock) {
            if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration())) {
                return new RegistrationResult(ApiStatus.SERVER_STOPPING, null);
            }
            registrations.put(uuid, new PendingRegistration(token, passwordHash));
        }
        return new RegistrationResult(ApiStatus.OK, token);
    }

    /** Called by the in-game command; file writes do not hold the HTTP authentication-state lock. */
    public static @NotNull ConfirmationResult confirmRegistration(@NotNull PlayerIdentity player,
        @NotNull String token) {
        PendingRegistration registration;
        synchronized (authenticationStateLock) {
            registration = registrations.get(player.uuid);
            if (registration == null) return ConfirmationResult.NOT_PENDING;
            if (!registration.token()
                .equals(token)) return ConfirmationResult.INVALID_TOKEN;
        }
        if (!CoreData.setPassword(player, registration.passwordHash())) return ConfirmationResult.STORAGE_UNAVAILABLE;
        synchronized (authenticationStateLock) {
            // Do not consume a newer registration submitted while account data was being saved.
            registrations.remove(player.uuid, registration);
        }
        return ConfirmationResult.SUCCESS;
    }

    public static boolean logout(@NotNull AE2Controller.RequestContext context) {
        HttpExchange exchange = context.getExchange();
        String token = extractToken(exchange);
        AuthSession session;
        SessionStore store;
        synchronized (authenticationStateLock) {
            if (!AE2Controller.isCurrentHTTPLifecycle(context.getLifecycleGeneration())) return false;
            session = token == null ? null : sessions.remove(SessionStore.hashToken(token));
            store = sessionStore;
            if (session != null) session.invalidate();
        }
        if (session != null && session.persistent() && store != null) {
            try {
                store.saveRevocation(System.currentTimeMillis());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
        return true;
    }

    public static synchronized void clearWorldState() {
        SessionStore store;
        Map<String, AuthSession> previous;
        synchronized (authenticationStateLock) {
            if (persistence != null) {
                persistence.shutdown();
                persistence = null;
            }
            store = sessionStore;
            sessionStore = null;
            adminCredential = null;
            credentialRateLimiter = null;
            registrations.clear();
            previous = sessions;
            sessions = new ConcurrentHashMap<>();
            localSession.invalidate();
        }
        if (store != null) {
            try {
                store.close(System.currentTimeMillis());
            } catch (IOException failure) {
                LOG.warn("Could not save remembered sessions on shutdown", failure);
            }
        }
        previous.values()
            .forEach(AuthSession::invalidate);
    }

    /** Checks may revoke changed credentials; only actual client requests renew idle expiry. */
    public static boolean isValid(@NotNull AuthSession session, long now) {
        if (!session.isValid(now)) return false;
        String credential = session.credential();
        if (credential != null && !credential.equals(credentialFor(session.principal()))) {
            session.invalidate();
            return false;
        }
        return true;
    }

    private static @Nullable String credentialFor(@NotNull WebPrincipal principal) {
        if (principal.isAdmin()) return Config.INSTANCE.general.password;
        PlayerIdentity player = principal.getPlayerIdentity();
        return Config.INSTANCE.general.publicMode && player != null ? CoreData.getCredentialVersion(player.uuid) : null;
    }

    private static void maintainSessions(@NotNull SessionStore store, @NotNull Map<String, AuthSession> ownedSessions) {
        boolean changed = false;
        boolean revoked = false;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AuthSession> entry : ownedSessions.entrySet()) {
            AuthSession session = entry.getValue();
            if (!isValid(session, now)) revoked |= ownedSessions.remove(entry.getKey(), session);
            else changed |= session.needsSave();
        }
        if (changed || revoked) store.markChanged(revoked);
        try {
            store.saveIfNeeded(now);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Could not update remembered sessions", failure);
        }
    }

    /** Credential mutation is rare; revoke before returning, including sessions retained by live streams. */
    public static void invalidatePlayer(@NotNull UUID player) {
        boolean changed = false;
        for (AuthSession session : sessions.values()) {
            PlayerIdentity identity = session.principal()
                .getPlayerIdentity();
            if (identity != null && identity.uuid.equals(player)) {
                session.invalidate();
                changed = true;
            }
        }
        SessionStore store = sessionStore;
        if (changed && store != null) {
            try {
                store.saveRevocation(System.currentTimeMillis());
            } catch (IOException failure) {
                LOG.warn("Could not persist player session revocation", failure);
            }
        }
    }

    private static void saveSessions(@NotNull SessionStore store) {
        try {
            store.save(System.currentTimeMillis());
        } catch (IOException | RuntimeException failure) {
            LOG.warn("Could not save remembered sessions", failure);
        }
    }
}
