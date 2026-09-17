package pl.kuba6000.ae2webintegration.core.auth;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.sun.net.httpserver.HttpExchange;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.AE2Controller.ServerTaskUnavailableException;
import pl.kuba6000.ae2webintegration.core.ClientAddressResolver;
import pl.kuba6000.ae2webintegration.core.PasswordHelper;
import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.utils.RateLimiter;

/** Shared account authentication and world-bound sessions for the API, website and game commands. */
public final class AuthService {

    private static final long SESSION_SECONDS = TimeUnit.HOURS.toSeconds(1);
    private static final long REMEMBER_ME_SESSION_SECONDS = TimeUnit.DAYS.toSeconds(7);
    private static final int RATE_LIMIT_WINDOW_MILLIS = (int) TimeUnit.MINUTES.toMillis(1);
    private static final int SESSION_TOKEN_LENGTH = 200;
    private static final int CONFIRMATION_TOKEN_LENGTH = 50;
    private static final Object authenticationStateLock = new Object();
    private static final ConcurrentHashMap<String, AuthSession> sessions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingRegistration> registrations = new ConcurrentHashMap<>();
    private static volatile RateLimiter rateLimiter = new RateLimiter(
        Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE(),
        RATE_LIMIT_WINDOW_MILLIS);
    private static volatile ClientAddressResolver clientAddressResolver = ClientAddressResolver.fromConfig("");

    private AuthService() {}

    @Desugar
    private record AuthSession(long expiresAtMillis, @NotNull WebPrincipal principal) {}

    @Desugar
    private record PendingRegistration(@NotNull String token, @NotNull String passwordHash) {}

    /** Token and principal are present exactly when status is OK. */
    @Desugar
    public record LoginResult(@NotNull ApiStatus status, @Nullable String token, @Nullable WebPrincipal principal,
        long validForSeconds) {

        private static @NotNull LoginResult failure(@NotNull ApiStatus status) {
            return new LoginResult(status, null, null, 0);
        }
    }

    /** The confirmation token is present exactly when status is OK. */
    @Desugar
    public record RegistrationResult(@NotNull ApiStatus status, @Nullable String token) {}

    public enum ConfirmationResult {
        SUCCESS,
        NOT_PENDING,
        INVALID_TOKEN
    }

    /** Reload changes request limits and proxy trust without invalidating existing sessions. */
    public static void reloadHttpSettings() {
        rateLimiter = new RateLimiter(Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE(), RATE_LIMIT_WINDOW_MILLIS);
        clientAddressResolver = ClientAddressResolver.fromConfig(Config.TRUSTED_PROXIES());
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

    /** Password checks remain behind the limiter; this check only reads existing credentials. */
    private static boolean isAlreadyIdentified(@NotNull HttpExchange exchange, @NotNull InetAddress client) {
        if (isLocalAccess(exchange, client)) return true;
        String token = extractToken(exchange);
        if (token == null) return false;
        AuthSession session = sessions.get(token);
        return session != null && System.currentTimeMillis() < session.expiresAtMillis();
    }

    private static boolean isLocalAccess(@NotNull HttpExchange exchange, @NotNull InetAddress client) {
        return !exchange.getRequestHeaders()
            .containsKey("Authorization") && Config.ALLOW_NO_PASSWORD_ON_LOCALHOST() && client.isLoopbackAddress();
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

    public static @Nullable RequestContext authenticate(@NotNull HttpExchange exchange) {
        if (isLocalAccess(exchange, resolveClientAddress(exchange))) {
            return new RequestContext(exchange, WebPrincipal.localhost());
        }
        String token = extractToken(exchange);
        if (token == null) return null;
        AuthSession session = sessions.get(token);
        if (session == null) return null;
        if (System.currentTimeMillis() >= session.expiresAtMillis()) {
            sessions.remove(token, session);
            return null;
        }
        return new RequestContext(exchange, session.principal());
    }

    /** Verifies credentials and publishes a session only into the originating HTTP lifecycle. */
    public static @NotNull LoginResult login(long generation, @NotNull String username, @NotNull String password,
        boolean rememberMe) {
        WebPrincipal principal;
        if (username.equalsIgnoreCase("admin") || !Config.AE_PUBLIC_MODE()) {
            if (!password.equals(Config.AE_PASSWORD()) && !Config.AE_PASSWORD()
                .isEmpty()) {
                return LoginResult.failure(ApiStatus.INVALID_PASSWORD);
            }
            principal = WebPrincipal.admin();
        } else {
            CoreData.Account account = CoreData.getAccount(username);
            if (account == null) return LoginResult.failure(ApiStatus.INVALID_USER);
            if (!CoreData.verifyPassword(account, password)) return LoginResult.failure(ApiStatus.INVALID_PASSWORD);
            principal = WebPrincipal.forPlayer(account.getIdentity());
        }
        String token = PasswordHelper.generateToken(SESSION_TOKEN_LENGTH);
        long seconds = rememberMe ? REMEMBER_ME_SESSION_SECONDS : SESSION_SECONDS;
        AuthSession session = new AuthSession(
            System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds),
            principal);
        synchronized (authenticationStateLock) {
            if (!AE2Controller.isCurrentHTTPLifecycle(generation))
                return LoginResult.failure(ApiStatus.SERVER_STOPPING);
            sessions.put(token, session);
        }
        return new LoginResult(ApiStatus.OK, token, principal, seconds);
    }

    /** The online-player lookup runs on the game thread; password hashing runs on the caller's HTTP thread. */
    public static @NotNull RegistrationResult register(long generation, @NotNull String username,
        @NotNull String password) {
        UUID uuid;
        try {
            uuid = AE2Controller.findOnlinePlayerOnServerThread(username);
        } catch (ServerTaskUnavailableException exception) {
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
            if (!AE2Controller.isCurrentHTTPLifecycle(generation)) {
                return new RegistrationResult(ApiStatus.SERVER_STOPPING, null);
            }
            registrations.put(uuid, new PendingRegistration(token, passwordHash));
        }
        return new RegistrationResult(ApiStatus.OK, token);
    }

    public static @NotNull ConfirmationResult confirmRegistration(@NotNull PlayerIdentity player,
        @NotNull String token) {
        synchronized (authenticationStateLock) {
            PendingRegistration registration = registrations.get(player.uuid);
            if (registration == null) return ConfirmationResult.NOT_PENDING;
            if (!registration.token()
                .equals(token)) return ConfirmationResult.INVALID_TOKEN;
            CoreData.setPassword(player, registration.passwordHash());
            registrations.remove(player.uuid);
            return ConfirmationResult.SUCCESS;
        }
    }

    public static void logout(@NotNull HttpExchange exchange) {
        String token = extractToken(exchange);
        if (token != null) sessions.remove(token);
    }

    public static void clearWorldState() {
        synchronized (authenticationStateLock) {
            registrations.clear();
            sessions.clear();
        }
    }
}
