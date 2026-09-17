package pl.kuba6000.ae2webintegration.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.endpoint.auth.Login;
import pl.kuba6000.ae2webintegration.core.http.endpoint.auth.Logout;
import pl.kuba6000.ae2webintegration.core.http.endpoint.auth.Register;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.CancelCPU;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.GetCPU;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.GetCPUList;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.CreateCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.DeleteCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.SubmitCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetGridSettings;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetGrids;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.GetItems;
import pl.kuba6000.ae2webintegration.core.http.endpoint.grid.PatchGridSettings;
import pl.kuba6000.ae2webintegration.core.http.endpoint.tracking.GetTracking;
import pl.kuba6000.ae2webintegration.core.http.endpoint.tracking.GetTrackingHistory;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;
import pl.kuba6000.ae2webintegration.core.utils.RateLimiter;

public class AE2Controller {

    public static IAE AE2Interface;
    public static IServerPlatform serverPlatform;

    private static HttpServer server;
    private static ExecutorService serverThread;
    private static final Object serverLifecycleLock = new Object();
    private static final Object authenticationStateLock = new Object();
    private static final AtomicLong httpLifecycleGeneration = new AtomicLong();
    private static volatile boolean acceptingHTTPRequests;
    private static final int HTTP_BACKLOG = 64;
    private static final int HTTP_CORE_THREADS = 8;
    private static final int HTTP_MAX_THREADS = 32;
    private static final int HTTP_QUEUE_CAPACITY = 32;
    private static final long HTTP_KEEP_ALIVE_SECONDS = 60L;

    public static UUID AEControllerUUID;

    public static PlayerIdentity AEControllerProfile;

    static {
        AEControllerUUID = UUID.nameUUIDFromBytes("AE2-WEB-INTEGRATION-AE2CONTROLLER".getBytes(StandardCharsets.UTF_8));
        AEControllerProfile = new PlayerIdentity(AEControllerUUID, "AE2CONTROLLER");
    }

    public static class RequestContext {

        private final WebPrincipal principal;
        private final HttpExchange exchange;
        private final Map<String, String> pathParams;
        private final JsonObject body;
        private final long lifecycleGeneration;

        public RequestContext(HttpExchange exchange, WebPrincipal principal) {
            this(exchange, principal, Collections.emptyMap(), null);
        }

        public RequestContext(HttpExchange exchange, WebPrincipal principal, Map<String, String> pathParams,
            JsonObject body) {
            this(exchange, principal, pathParams, body, httpLifecycleGeneration.get());
        }

        private RequestContext(HttpExchange exchange, WebPrincipal principal, Map<String, String> pathParams,
            JsonObject body, long lifecycleGeneration) {
            this.principal = principal;
            this.exchange = exchange;
            this.pathParams = pathParams;
            this.body = body;
            this.lifecycleGeneration = lifecycleGeneration;
        }

        /** Preserves the request's originating lifecycle while adding authenticated, parsed input. */
        public RequestContext withInputs(WebPrincipal principal, Map<String, String> pathParams, JsonObject body) {
            return new RequestContext(exchange, principal, pathParams, body, lifecycleGeneration);
        }

        public long getLifecycleGeneration() {
            return lifecycleGeneration;
        }

        public Map<String, String> getPathParams() {
            return pathParams;
        }

        public JsonObject getBody() {
            return body;
        }

        public HttpExchange getExchange() {
            return exchange;
        }

        public WebPrincipal getPrincipal() {
            return principal;
        }

        public boolean isAdmin() {
            return principal.isAdmin();
        }
    }

    static ThreadLocal<RequestContext> requestContext = new ThreadLocal<>();

    public static ConcurrentHashMap<UUID, Pair<String, String>> awaitingRegistration = new ConcurrentHashMap<>();

    // Package-private: the tick pump in CoreEngine is the only consumer, and after X-01 nothing outside
    // core touches the queue at all.
    private static final int SERVER_THREAD_QUEUE_CAPACITY = 32;
    static final BlockingQueue<IServerThreadTask> requests = new ArrayBlockingQueue<>(SERVER_THREAD_QUEUE_CAPACITY);

    private static final long AUTH_LOOKUP_TIMEOUT_SECONDS = 2L;
    private static final long REQUEST_TIMEOUT_SECONDS = 10L;
    private static final long SESSION_SECONDS = TimeUnit.HOURS.toSeconds(1);
    private static final long REMEMBER_ME_SESSION_SECONDS = TimeUnit.DAYS.toSeconds(7);
    private static final int RATE_LIMIT_WINDOW_MILLIS = (int) TimeUnit.MINUTES.toMillis(1);
    private static final int SESSION_TOKEN_LENGTH = 200;
    private static final int CONFIRMATION_TOKEN_LENGTH = 50;

    private static final class ServerTaskUnavailableException extends Exception {

        @SuppressWarnings("MissingSerialAnnotation") // @Serial is unavailable on Java 8.
        private static final long serialVersionUID = 1L;

        private final ApiStatus status;

        private ServerTaskUnavailableException(ApiStatus status) {
            super(status.name());
            this.status = status;
        }
    }

    private static final class OnlinePlayerLookupTask implements IServerThreadTask {

        private final String username;
        private final CompletableFuture<UUID> result = new CompletableFuture<>();

        private OnlinePlayerLookupTask(String username) {
            this.username = username;
        }

        @Override
        public void runOnServerThread(IAE ae) {
            if (!result.isDone()) {
                result.complete(serverPlatform.getOnlinePlayerUUID(username));
            }
        }

        @Override
        public void failIfPending(ApiStatus status) {
            result.completeExceptionally(new ServerTaskUnavailableException(status));
        }
    }

    // Rebuilt in startHTTPServer() so /reload picks up config changes, and so two concurrent first
    // requests cannot race to create two limiters with split counters.
    private static volatile RateLimiter rateLimiter = new RateLimiter(
        Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE(),
        RATE_LIMIT_WINDOW_MILLIS);
    private static volatile ClientAddressResolver clientAddressResolver = ClientAddressResolver.fromConfig("");

    /**
     * The address to treat this request as coming from. Behind a reverse proxy the TCP peer is always the
     * proxy, so every decision about the caller's identity - the localhost trust check and rate limiting
     * alike - has to go through here, or the two would disagree.
     */
    static InetAddress resolveClientAddress(HttpExchange t) {
        return clientAddressResolver.resolve(
            t.getRemoteAddress()
                .getAddress(),
            t.getLocalAddress()
                .getAddress(),
            t.getRequestHeaders()
                .get("X-Forwarded-For"),
            t.getRequestHeaders()
                .get("X-Real-IP"));
    }

    /**
     * Cheap, read-only check for "this caller is already known": a valid session token, or loopback when
     * password-less local access is enabled. Deliberately does not verify passwords - PBKDF2 must stay
     * behind the rate limiter - and does not mutate token state or send a response.
     */
    private static boolean isAlreadyIdentified(HttpExchange t, InetAddress client) {
        if (!t.getRequestHeaders()
            .containsKey("Authorization") && Config.ALLOW_NO_PASSWORD_ON_LOCALHOST() && client.isLoopbackAddress()) {
            return true;
        }
        String token = extractToken(t);
        if (token == null) {
            return false;
        }
        AuthSession session = validTokens.get(token);
        return session != null && System.currentTimeMillis() < session.expiresAtMillis;
    }

    private static final int MAX_BODY_BYTES = 8 * 1024;

    /**
     * Reads a request body the way an unauthenticated boundary has to: bounded, explicitly UTF-8, and
     * without throwing on anything a client might send. An empty body is a legitimate input and yields an
     * empty string rather than an exception.
     *
     * @return the decoded body, or {@code null} when it is larger than {@link #MAX_BODY_BYTES}.
     */
    private static String readBody(HttpExchange t) throws IOException {
        try (InputStream in = t.getRequestBody()) {
            // One byte past the limit is enough to detect oversize without buffering the rest.
            byte[] buffer = new byte[MAX_BODY_BYTES + 1];
            int read = 0;
            while (read < buffer.length) {
                int count = in.read(buffer, read, buffer.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            if (read > MAX_BODY_BYTES) {
                return null;
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static String extractToken(HttpExchange exchange) {
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

    /** Resolves credentials only; page forms and logout redirects are deliberately separate. */
    private static RequestContext authenticateApi(HttpExchange exchange) {
        InetAddress client = resolveClientAddress(exchange);
        if (!exchange.getRequestHeaders()
            .containsKey("Authorization") && Config.ALLOW_NO_PASSWORD_ON_LOCALHOST() && client.isLoopbackAddress()) {
            return new RequestContext(exchange, WebPrincipal.localhost());
        }
        String token = extractToken(exchange);
        if (token == null) return null;
        AuthSession session = validTokens.get(token);
        if (session == null) return null;
        if (System.currentTimeMillis() >= session.expiresAtMillis) {
            validTokens.remove(token, session);
            return null;
        }
        return new RequestContext(exchange, session.principal);
    }

    public static void startHTTPServer() {
        synchronized (serverLifecycleLock) {
            if (server != null || serverThread != null) {
                throw new IllegalStateException("HTTP server is already running");
            }

            rateLimiter = new RateLimiter(
                Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE(),
                RATE_LIMIT_WINDOW_MILLIS);
            clientAddressResolver = ClientAddressResolver.fromConfig(Config.TRUSTED_PROXIES());
            ExecutorService newServerThread = createHTTPExecutor();
            HttpServer newServer = null;
            try {
                newServer = HttpServer.create(new InetSocketAddress(Config.AE_PORT()), HTTP_BACKLOG);
                ApiRouter api = new ApiRouter(
                    AE2Controller::authenticateApi,
                    exchange -> !isAlreadyIdentified(exchange, resolveClientAddress(exchange))
                        && !rateLimiter.isAllowed(resolveClientAddress(exchange)),
                    AE2Controller::sendRequest);
                api.register(GetGrids.class);
                api.register(GetCPUList.class);
                api.register(GetCPU.class);
                api.register(CancelCPU.class);
                api.register(GetItems.class);
                api.register(GetGridSettings.class);
                api.register(PatchGridSettings.class);
                api.register(CreateCraftingPlan.class);
                api.register(GetCraftingPlan.class);
                api.register(SubmitCraftingPlan.class);
                api.register(DeleteCraftingPlan.class);
                api.register(GetTrackingHistory.class);
                api.register(GetTracking.class);
                api.register(Login.class);
                api.register(Register.class);
                api.register(Logout.class);
                newServer.createContext("/api", api);
                newServer.createContext("/", new WebHandler());
                newServer.setExecutor(newServerThread);
                httpLifecycleGeneration.incrementAndGet();
                acceptingHTTPRequests = true;
                newServer.start();
                server = newServer;
                serverThread = newServerThread;
            } catch (IOException e) {
                abortHTTPServerStart(newServer, newServerThread);
                throw new RuntimeException(e);
            } catch (RuntimeException e) {
                abortHTTPServerStart(newServer, newServerThread);
                throw e;
            }
        }
    }

    public static void stopHTTPServer() {
        synchronized (serverLifecycleLock) {
            acceptingHTTPRequests = false;
            httpLifecycleGeneration.incrementAndGet();
            if (server != null) {
                server.stop(0);
            }
            IServerThreadTask task;
            while ((task = requests.poll()) != null) {
                task.failIfPending(ApiStatus.SERVER_STOPPING);
            }
            shutdownHTTPExecutor(serverThread);
            server = null;
            serverThread = null;
        }
    }

    private static void abortHTTPServerStart(HttpServer newServer, ExecutorService newServerThread) {
        acceptingHTTPRequests = false;
        httpLifecycleGeneration.incrementAndGet();
        if (newServer != null) {
            newServer.stop(0);
        }
        newServerThread.shutdownNow();
    }

    static ExecutorService createHTTPExecutor() {
        return new ThreadPoolExecutor(
            HTTP_CORE_THREADS,
            HTTP_MAX_THREADS,
            HTTP_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY)) {

            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                super.afterExecute(r, t);
                requestContext.remove();
            }
        };
    }

    private static void shutdownHTTPExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                // The final bounded wait is best-effort; shutdownNow has already requested interruption.
                // noinspection ResultOfMethodCallIgnored
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread()
                .interrupt();
        }
    }

    static void clearWorldState() {
        IServerThreadTask task;
        while ((task = requests.poll()) != null) {
            task.failIfPending(ApiStatus.SERVER_STOPPING);
        }
        synchronized (authenticationStateLock) {
            awaitingRegistration.clear();
            validTokens.clear();
        }
        itemIdentities.clear();
        requestContext.remove();
    }

    public static final ItemIdentityRegistry itemIdentities = new ItemIdentityRegistry();

    private static final class AuthSession {

        private final long expiresAtMillis;
        private final WebPrincipal principal;

        private AuthSession(long expiresAtMillis, WebPrincipal principal) {
            this.expiresAtMillis = expiresAtMillis;
            this.principal = principal;
        }
    }

    private static final class LoginResult {

        private final WebPrincipal principal;
        private final ApiStatus error;

        private LoginResult(WebPrincipal principal, ApiStatus error) {
            this.principal = principal;
            this.error = error;
        }

        private static LoginResult success(WebPrincipal principal) {
            return new LoginResult(principal, null);
        }

        private static LoginResult failure(ApiStatus error) {
            return new LoginResult(null, error);
        }

        private boolean succeeded() {
            return error == null;
        }
    }

    private static final class RegistrationResult {

        private final UUID playerUuid;
        private final String passwordHash;
        private final ApiStatus error;
        private final boolean serviceUnavailable;

        private RegistrationResult(UUID playerUuid, String passwordHash, ApiStatus error, boolean serviceUnavailable) {
            this.playerUuid = playerUuid;
            this.passwordHash = passwordHash;
            this.error = error;
            this.serviceUnavailable = serviceUnavailable;
        }

        private static RegistrationResult success(UUID playerUuid, String passwordHash) {
            return new RegistrationResult(playerUuid, passwordHash, null, false);
        }

        private static RegistrationResult failure(ApiStatus error, boolean serviceUnavailable) {
            return new RegistrationResult(null, null, error, serviceUnavailable);
        }

        private boolean succeeded() {
            return error == null;
        }
    }

    private static final ConcurrentHashMap<String, AuthSession> validTokens = new ConcurrentHashMap<>();

    /**
     * Lax, which is also what browsers apply to a cookie with no SameSite at all since Chrome 80 - so
     * stating it changes little today beyond covering older browsers. Strict would additionally block a
     * top-level navigation from another site, but it costs a login screen whenever someone follows a link
     * here. Migrated state-changing API routes must also enforce a CSRF policy; changing the HTTP
     * method alone does not provide that protection.
     * <p>
     * Deliberately no Secure attribute: the server speaks plain HTTP, and the cookie would then never be
     * sent at all.
     */
    private static String sessionCookie(String token, long maxAgeSeconds) {
        return "authenticationToken=" + token + "; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Lax";
    }

    private static LoginResult authenticateLogin(String requestedUsername, String password) {
        if (requestedUsername.equalsIgnoreCase("admin") || !Config.AE_PUBLIC_MODE()) {
            if (!password.equals(Config.AE_PASSWORD()) && !Config.AE_PASSWORD()
                .isEmpty()) {
                return LoginResult.failure(ApiStatus.INVALID_PASSWORD);
            }
            return LoginResult.success(WebPrincipal.admin());
        }

        CoreData.Account account = CoreData.getAccount(requestedUsername);
        if (account == null) {
            return LoginResult.failure(ApiStatus.INVALID_USER);
        }
        if (!CoreData.verifyPassword(account, password)) {
            return LoginResult.failure(ApiStatus.INVALID_PASSWORD);
        }
        return LoginResult.success(WebPrincipal.forPlayer(account.getIdentity()));
    }

    private static RegistrationResult prepareRegistration(String username, String password) {
        UUID playerUuid;
        try {
            playerUuid = findOnlinePlayerOnServerThread(username);
        } catch (ServerTaskUnavailableException e) {
            return RegistrationResult.failure(e.status, true);
        }
        if (playerUuid == null) {
            return RegistrationResult.failure(ApiStatus.NOT_ONLINE, false);
        }
        try {
            return RegistrationResult.success(playerUuid, PasswordHelper.generateStrongPasswordHash(password));
        } catch (Exception e) {
            return RegistrationResult.failure(ApiStatus.INVALID_PASSWORD, false);
        }
    }

    /** Issues a token without setting a browser cookie; page forms own their externally visible cookie path. */
    public static ApiResponse loginApi(RequestContext context, String username, String password, boolean rememberMe) {
        long generation = context.getLifecycleGeneration();
        LoginResult login = authenticateLogin(username, password);
        if (!login.succeeded()) return ApiResponse.error(login.error);
        String token = PasswordHelper.generateToken(SESSION_TOKEN_LENGTH);
        long seconds = rememberMe ? REMEMBER_ME_SESSION_SECONDS : SESSION_SECONDS;
        AuthSession session = new AuthSession(
            System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds),
            login.principal);
        if (!publishToken(generation, token, session)) return ApiResponse.error(ApiStatus.SERVER_STOPPING);
        Login.Session data = new Login.Session(
            token,
            login.principal.getUsername(),
            login.principal.isAdmin(),
            Config.CHECK_FOR_UPDATES() && CoreEngine.getAvailableUpdate() != null);
        return ApiResponse.of(HttpURLConnection.HTTP_OK, new Login.Response(ApiStatus.OK, data));
    }

    /** Registration retains the bounded game-thread lookup and listener-generation publication fence. */
    public static ApiResponse registerApi(RequestContext context, String username, String password) {
        long generation = context.getLifecycleGeneration();
        RegistrationResult registration = prepareRegistration(username, password);
        if (!registration.succeeded()) {
            if (registration.serviceUnavailable || registration.error == ApiStatus.NOT_ONLINE)
                return ApiResponse.error(registration.error);
            return ApiResponse.of(HttpURLConnection.HTTP_BAD_REQUEST, new ErrorResponse(registration.error, null));
        }
        String token = PasswordHelper.generateToken(CONFIRMATION_TOKEN_LENGTH);
        if (!publishRegistration(generation, registration.playerUuid, Pair.of(token, registration.passwordHash))) {
            return ApiResponse.error(ApiStatus.SERVER_STOPPING);
        }
        return ApiResponse
            .of(HttpURLConnection.HTTP_ACCEPTED, new Register.Response(ApiStatus.OK, new Register.Confirmation(token)));
    }

    public static void logoutApi(RequestContext context) {
        String token = extractToken(context.getExchange());
        if (token != null) validTokens.remove(token);
    }

    private static boolean isSameOriginBrowserPost(HttpExchange exchange) {
        String site = exchange.getRequestHeaders()
            .getFirst("Sec-Fetch-Site");
        if (site != null) return site.equals("same-origin") || site.equals("none");
        String origin = exchange.getRequestHeaders()
            .getFirst("Origin");
        if (origin == null) return true; // Non-browser clients and older browsers without Fetch Metadata.
        try {
            String scheme = "http";
            if (clientAddressResolver.isTrustedProxy(
                exchange.getRemoteAddress()
                    .getAddress(),
                exchange.getLocalAddress()
                    .getAddress())) {
                List<String> forwarded = exchange.getRequestHeaders()
                    .get("X-Forwarded-Proto");
                if (forwarded != null) {
                    // The trusted proxy must overwrite this header with the external protocol.
                    if (forwarded.size() != 1) return false;
                    scheme = forwarded.get(0)
                        .trim();
                    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return false;
                }
            }
            String host = exchange.getRequestHeaders()
                .getFirst("Host");
            if (host == null) return false;
            URI source = URI.create(origin);
            URI target = URI.create(scheme + "://" + host);
            int defaultPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            int sourcePort = source.getPort() < 0 ? defaultPort : source.getPort();
            int targetPort = target.getPort() < 0 ? defaultPort : target.getPort();
            return scheme.equalsIgnoreCase(source.getScheme()) && source.getHost() != null
                && source.getRawUserInfo() == null
                && source.getRawPath()
                    .isEmpty()
                && source.getRawQuery() == null
                && source.getRawFragment() == null
                && target.getRawUserInfo() == null
                && target.getRawPath()
                    .isEmpty()
                && target.getRawQuery() == null
                && target.getRawFragment() == null
                && source.getHost()
                    .equalsIgnoreCase(target.getHost())
                && sourcePort == targetPort;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private enum AuthCheckResult {
        AUTHENTICATED,
        UNAUTHENTICATED,
        RESPONSE_SENT
    }

    private static AuthCheckResult checkAuth(HttpExchange t) throws IOException {
        long requestLifecycleGeneration = httpLifecycleGeneration.get();
        RequestContext authenticated = authenticateApi(t);
        if (authenticated != null) {
            requestContext.set(authenticated);
            return AuthCheckResult.AUTHENTICATED;
        }
        if (t.getRequestHeaders()
            .containsKey("Authorization")) return AuthCheckResult.UNAUTHENTICATED;
        String cookieToken = extractToken(t);
        if (cookieToken != null) {
            t.getResponseHeaders()
                .add("Set-Cookie", sessionCookie(cookieToken, -1));
            return AuthCheckResult.UNAUTHENTICATED;
        }
        if (t.getRequestMethod()
            .equals("POST")) {
            if (!isSameOriginBrowserPost(t)) {
                t.sendResponseHeaders(HttpURLConnection.HTTP_FORBIDDEN, -1);
                return AuthCheckResult.RESPONSE_SENT;
            }
            String postRaw = readBody(t);
            // Oversize is treated as no usable body: the branches below simply will not match and the
            // existing flow answers 401. checkAuth must not send its own response here - see C-25.
            Map<String, String> postData = HTTPUtils.parseQueryString(postRaw);

            if (postData.containsKey("register") && postData.containsKey("password")) {
                RegistrationResult registration = prepareRegistration(
                    postData.get("register"),
                    postData.get("password"));
                if (!registration.succeeded() && registration.serviceUnavailable) {
                    sendServerUnavailable(t, registration.error);
                    return AuthCheckResult.RESPONSE_SENT;
                }
                if (!registration.succeeded()) {
                    t.getResponseHeaders()
                        .add("Location", "?" + registration.error.name());
                    t.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
                    return AuthCheckResult.RESPONSE_SENT;
                }

                String confirmationToken = PasswordHelper.generateToken(CONFIRMATION_TOKEN_LENGTH);
                Pair<String, String> pending = Pair.of(confirmationToken, registration.passwordHash);
                if (!publishRegistration(requestLifecycleGeneration, registration.playerUuid, pending)) {
                    sendServerStopping(t);
                    return AuthCheckResult.RESPONSE_SENT;
                }
                t.getResponseHeaders()
                    .add("Location", "?confirmregistration&token=" + confirmationToken);
                t.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
                return AuthCheckResult.RESPONSE_SENT; // Registration initiated
            }

            if (postData.containsKey("password") && postData.containsKey("username")) {
                LoginResult login = authenticateLogin(postData.get("username"), postData.get("password"));
                if (!login.succeeded()) {
                    t.getResponseHeaders()
                        .add("Location", "?" + login.error.name());
                    t.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
                    return AuthCheckResult.RESPONSE_SENT;
                }
                boolean rememberMe = postData.containsKey("remember");
                String token = PasswordHelper.generateToken(SESSION_TOKEN_LENGTH);
                long validFor = rememberMe ? REMEMBER_ME_SESSION_SECONDS : SESSION_SECONDS;
                AuthSession session = new AuthSession(
                    System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(validFor),
                    login.principal);
                if (!publishToken(requestLifecycleGeneration, token, session)) {
                    sendServerStopping(t);
                    return AuthCheckResult.RESPONSE_SENT;
                }
                t.getResponseHeaders()
                    .add("Set-Cookie", sessionCookie(token, validFor));
                t.getResponseHeaders()
                    .add("Location", ".");
                t.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
                return AuthCheckResult.RESPONSE_SENT;
            }
        }
        return AuthCheckResult.UNAUTHENTICATED;
    }

    private static ApiStatus enqueueServerThreadTask(IServerThreadTask task) {
        if (!acceptingHTTPRequests) {
            task.failIfPending(ApiStatus.SERVER_STOPPING);
            return ApiStatus.SERVER_STOPPING;
        }
        if (!requests.offer(task)) {
            ApiStatus status = acceptingHTTPRequests ? ApiStatus.SERVER_BUSY : ApiStatus.SERVER_STOPPING;
            task.failIfPending(status);
            return status;
        }
        if (!acceptingHTTPRequests && requests.remove(task)) {
            task.failIfPending(ApiStatus.SERVER_STOPPING);
            return ApiStatus.SERVER_STOPPING;
        }
        return null;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored") // The task may already be dequeued; failIfPending handles that race.
    private static UUID findOnlinePlayerOnServerThread(String username) throws ServerTaskUnavailableException {
        OnlinePlayerLookupTask task = new OnlinePlayerLookupTask(username);
        ApiStatus unavailableStatus = enqueueServerThreadTask(task);
        if (unavailableStatus != null) {
            throw new ServerTaskUnavailableException(unavailableStatus);
        }
        try {
            return task.result.get(AUTH_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            requests.remove(task);
            task.failIfPending(ApiStatus.SERVER_BUSY);
            throw new ServerTaskUnavailableException(ApiStatus.SERVER_BUSY);
        } catch (InterruptedException e) {
            requests.remove(task);
            task.failIfPending(ApiStatus.SERVER_STOPPING);
            Thread.currentThread()
                .interrupt();
            throw new ServerTaskUnavailableException(ApiStatus.SERVER_STOPPING);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ServerTaskUnavailableException) {
                throw (ServerTaskUnavailableException) cause;
            }
            throw new ServerTaskUnavailableException(ApiStatus.INTERNAL_ERROR);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored") // Removal is best-effort; the pending result is failed either way.
    private static boolean sendRequest(ISyncedRequest request) {
        if (enqueueServerThreadTask(request) != null) {
            return true;
        }
        try {
            request.awaitCompletion(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            requests.remove(request);
            request.failIfPending(ApiStatus.TIMEOUT);
        } catch (InterruptedException e) {
            requests.remove(request);
            request.failIfPending(ApiStatus.SERVER_STOPPING);
            Thread.currentThread()
                .interrupt();
            return true;
        }
        return false;
    }

    private static boolean publishRegistration(long generation, UUID uuid, Pair<String, String> registration) {
        synchronized (authenticationStateLock) {
            if (!isCurrentHTTPLifecycle(generation)) {
                return false;
            }
            awaitingRegistration.put(uuid, registration);
            return true;
        }
    }

    private static boolean publishToken(long generation, String token, AuthSession session) {
        synchronized (authenticationStateLock) {
            if (!isCurrentHTTPLifecycle(generation)) {
                return false;
            }
            validTokens.put(token, session);
            return true;
        }
    }

    private static boolean isCurrentHTTPLifecycle(long generation) {
        return acceptingHTTPRequests && httpLifecycleGeneration.get() == generation;
    }

    private static void sendServerStopping(HttpExchange exchange) throws IOException {
        sendServerUnavailable(exchange, ApiStatus.SERVER_STOPPING);
    }

    private static void sendServerUnavailable(HttpExchange exchange, ApiStatus status) throws IOException {
        byte[] response = status.name()
            .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAVAILABLE, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    static class WebHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {

            InetAddress client = resolveClientAddress(t);
            if (!isAlreadyIdentified(t, client) && !rateLimiter.isAllowed(client)) {
                byte[] raw_response = "Too Many Requests".getBytes(StandardCharsets.UTF_8);
                t.getResponseHeaders()
                    .add("Content-Type", "text/plain");
                t.sendResponseHeaders(429, raw_response.length); // NOPMD - HTTP Too Many Requests.
                OutputStream os = t.getResponseBody();
                os.write(raw_response);
                os.close();
                return;
            }

            String path = t.getRequestURI()
                .getPath();

            if (path.equals("/favicon.ico")) {
                t.getResponseHeaders()
                    .set("Content-Type", "image/x-icon");
                try (InputStream is = AE2Controller.class.getResourceAsStream("/assets/favicon.ico")) {
                    if (is == null) return;

                    byte[] raw_response = IOUtils.toByteArray(is);
                    t.sendResponseHeaders(HttpURLConnection.HTTP_OK, raw_response.length);
                    OutputStream os = t.getResponseBody();
                    os.write(raw_response);
                    os.close();
                }
                return;
            }

            // only accept index file
            if (!path.equals("/") && !path.isEmpty()
                && !path.equals("/index.php")
                && !path.equals("/index.html")
                && !path.equals("/index.htm")
                && !path.equals("/index.asp")
                && !path.equals("/index.aspx")
                && !path.equals("/index.jsp")) {

                String response = "<h1>Invalid url! (ERROR 404)</h1>";
                byte[] raw_response = response.getBytes(StandardCharsets.UTF_8);
                t.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, raw_response.length);
                OutputStream os = t.getResponseBody();
                os.write(raw_response);
                os.close();
                return;
            }

            String site = "/assets/webpage.html";

            AuthCheckResult authResult = checkAuth(t);
            if (authResult == AuthCheckResult.RESPONSE_SENT) {
                return;
            }
            if (authResult == AuthCheckResult.UNAUTHENTICATED) {
                site = "/assets/login.html";
            }

            String response;
            try (InputStream is = AE2Controller.class.getResourceAsStream(site)) {
                if (is == null) return;
                try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                    BufferedReader reader = new BufferedReader(isr)) {
                    response = reader.lines()
                        .collect(Collectors.joining(System.lineSeparator()));
                }
            }
            response = response.replace("_REPLACE_ME_IS_PUBLIC_MODE", Config.AE_PUBLIC_MODE() ? "true" : "false");
            response = response.replace(
                "_REPLACE_ME_VERSION_OUTDATED",
                Config.CHECK_FOR_UPDATES() && CoreEngine.getAvailableUpdate() != null ? "true" : "false");
            RequestContext context = requestContext.get();
            if (context != null) {
                response = response.replace("_REPLACE_ME_USERNAME", context.principal.getUsername());
                response = response.replace("_REPLACE_ME_IS_ADMIN", context.isAdmin() ? "true" : "false");
            }
            byte[] raw_response = response.getBytes(StandardCharsets.UTF_8);
            t.getResponseHeaders()
                .set("Content-Type", "text/html; charset=UTF-8");
            t.sendResponseHeaders(HttpURLConnection.HTTP_OK, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }

    }

    public static void init() {
        try {
            startHTTPServer();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

}
