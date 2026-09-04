package pl.kuba6000.ae2webintegration.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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

import pl.kuba6000.ae2webintegration.core.ae2request.async.GetTracking;
import pl.kuba6000.ae2webintegration.core.ae2request.async.GetTrackingHistory;
import pl.kuba6000.ae2webintegration.core.ae2request.async.GridSettings;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.CancelCPU;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetCPU;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetCPUList;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetGridList;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetItems;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.Job;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.Order;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;
import pl.kuba6000.ae2webintegration.core.utils.RateLimiter;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

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
        try {
            AEControllerUUID = UUID.nameUUIDFromBytes("AE2-WEB-INTEGRATION-AE2CONTROLLER".getBytes("UTF-8"));
            AEControllerProfile = new PlayerIdentity(AEControllerUUID, "AE2CONTROLLER");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static class RequestContext {

        private final HttpExchange exchange;
        private final Map<String, String> getParams;
        private final WebPrincipal principal;

        public RequestContext(HttpExchange exchange, WebPrincipal principal) {
            this.exchange = exchange;
            this.getParams = HTTPUtils.parseQueryString(
                exchange.getRequestURI()
                    .getQuery());
            this.principal = principal;
        }

        public HttpExchange getExchange() {
            return exchange;
        }

        public Map<String, String> getGetParams() {
            return getParams;
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

    private static final class ServerTaskUnavailableException extends Exception {

        private static final long serialVersionUID = 1L;

        private final String status;

        private ServerTaskUnavailableException(String status) {
            super(status);
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
        public void failIfPending(String status) {
            result.completeExceptionally(new ServerTaskUnavailableException(status));
        }
    }

    // Rebuilt in startHTTPServer() so /reload picks up config changes, and so two concurrent first
    // requests cannot race to create two limiters with split counters.
    private static volatile RateLimiter rateLimiter = new RateLimiter(20, 60 * 1000);
    private static volatile ClientAddressResolver clientAddressResolver = ClientAddressResolver.fromConfig("");

    /**
     * The address to treat this request as coming from. Behind a reverse proxy the TCP peer is always the
     * proxy, so every decision about who the caller is - the localhost trust check and rate limiting
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
        if (Config.ALLOW_NO_PASSWORD_ON_LOCALHOST() && client.isLoopbackAddress()) {
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

    private static String extractToken(HttpExchange t) {
        List<String> auth = t.getRequestHeaders()
            .get("Authorization");
        if (auth != null && !auth.isEmpty()) {
            return auth.get(0)
                .replace("Bearer ", "");
        }
        List<String> cookies = t.getRequestHeaders()
            .get("Cookie");
        if (cookies != null && !cookies.isEmpty()) {
            for (String cookie : cookies.get(0)
                .split("; ")) {
                if (cookie.startsWith("authenticationToken=")) {
                    return cookie.substring("authenticationToken=".length());
                }
            }
        }
        return null;
    }

    public static void startHTTPServer() {
        synchronized (serverLifecycleLock) {
            if (server != null || serverThread != null) {
                throw new IllegalStateException("HTTP server is already running");
            }

            rateLimiter = new RateLimiter(Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE(), 60 * 1000);
            clientAddressResolver = ClientAddressResolver.fromConfig(Config.TRUSTED_PROXIES());
            ExecutorService newServerThread = createHTTPExecutor();
            HttpServer newServer = null;
            try {
                newServer = HttpServer.create(new InetSocketAddress(Config.AE_PORT()), HTTP_BACKLOG);
                newServer.createContext("/grids", new SyncedRequestHandler(GetGridList.class));
                newServer.createContext("/list", new SyncedRequestHandler(GetCPUList.class));
                newServer.createContext("/get", new SyncedRequestHandler(GetCPU.class));
                newServer.createContext("/cancelcpu", new SyncedRequestHandler(CancelCPU.class));
                newServer.createContext("/items", new SyncedRequestHandler(GetItems.class));
                newServer.createContext("/order", new SyncedRequestHandler(Order.class));
                newServer.createContext("/job", new SyncedRequestHandler(Job.class));
                newServer.createContext("/trackinghistory", new ASyncRequestHandler(GetTrackingHistory.class));
                newServer.createContext("/gettracking", new ASyncRequestHandler(GetTracking.class));
                newServer.createContext("/gridsettings", new ASyncRequestHandler(GridSettings.class));
                newServer.createContext("/auth", new AuthHandler());
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
                task.failIfPending("SERVER_STOPPING");
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
            new ArrayBlockingQueue<Runnable>(HTTP_QUEUE_CAPACITY)) {

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
            task.failIfPending("SERVER_STOPPING");
        }
        synchronized (authenticationStateLock) {
            awaitingRegistration.clear();
            validTokens.clear();
        }
        itemIdentities.clear();
        requestContext.remove();
    }

    public static final ItemIdentityRegistry itemIdentities = new ItemIdentityRegistry(
        65_536,
        64L * 1024 * 1024,
        30L * 60 * 1000,
        System::currentTimeMillis);

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
        private final String error;

        private LoginResult(WebPrincipal principal, String error) {
            this.principal = principal;
            this.error = error;
        }

        private static LoginResult success(WebPrincipal principal) {
            return new LoginResult(principal, null);
        }

        private static LoginResult failure(String error) {
            return new LoginResult(null, error);
        }

        private boolean succeeded() {
            return error == null;
        }
    }

    private static final class RegistrationResult {

        private final UUID playerUuid;
        private final String passwordHash;
        private final String error;
        private final boolean serviceUnavailable;

        private RegistrationResult(UUID playerUuid, String passwordHash, String error, boolean serviceUnavailable) {
            this.playerUuid = playerUuid;
            this.passwordHash = passwordHash;
            this.error = error;
            this.serviceUnavailable = serviceUnavailable;
        }

        private static RegistrationResult success(UUID playerUuid, String passwordHash) {
            return new RegistrationResult(playerUuid, passwordHash, null, false);
        }

        private static RegistrationResult failure(String error, boolean serviceUnavailable) {
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
     * here, and the endpoints it would protect should stop being GETs instead. See the plan for moving
     * state-changing operations to POST, which is what actually closes this.
     * <p>
     * Deliberately no Secure attribute: the server speaks plain HTTP, and the cookie would then never be
     * sent at all.
     */
    private static String sessionCookie(String token, long maxAgeSeconds) {
        return "authenticationToken=" + token + "; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Lax";
    }

    private static String generateToken() {
        return generateToken(200);
    }

    private static String generateToken(int limit) {
        return new SecureRandom().ints(48, 122 + 1)
            .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
            .limit(limit)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }

    private static LoginResult authenticateLogin(String requestedUsername, String password) {
        if (requestedUsername.equalsIgnoreCase("admin") || !Config.AE_PUBLIC_MODE()) {
            if (!password.equals(Config.AE_PASSWORD()) && !Config.AE_PASSWORD()
                .isEmpty()) {
                return LoginResult.failure("invalidpassword");
            }
            return LoginResult.success(WebPrincipal.admin());
        }

        CoreData.Account account = CoreData.getAccount(requestedUsername);
        if (account == null) {
            return LoginResult.failure("invaliduser");
        }
        if (!CoreData.verifyPassword(account, password)) {
            return LoginResult.failure("invalidpassword");
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
            return RegistrationResult.failure("notonline", false);
        }
        try {
            return RegistrationResult.success(playerUuid, PasswordHelper.generateStrongPasswordHash(password));
        } catch (Exception e) {
            return RegistrationResult.failure("invalidpassword", false);
        }
    }

    private enum AuthCheckResult {
        AUTHENTICATED,
        UNAUTHENTICATED,
        RESPONSE_SENT
    }

    private static AuthCheckResult checkAuth(HttpExchange t) throws IOException {
        long requestLifecycleGeneration = httpLifecycleGeneration.get();
        InetAddress client = resolveClientAddress(t);

        if (Config.ALLOW_NO_PASSWORD_ON_LOCALHOST() && client.isLoopbackAddress()) {
            requestContext.set(new RequestContext(t, WebPrincipal.localhost()));
            return AuthCheckResult.AUTHENTICATED;
        }

        // Alternative authentication method
        List<String> auth = t.getRequestHeaders()
            .get("Authorization");
        if (auth != null && !auth.isEmpty()) {
            String token = auth.get(0);
            token = token.replace("Bearer ", "");
            AuthSession session = validTokens.get(token);
            if (session != null) {
                long validity = session.expiresAtMillis;
                if (System.currentTimeMillis() < validity) {
                    requestContext.set(new RequestContext(t, session.principal));
                    return AuthCheckResult.AUTHENTICATED; // Token is valid
                } else {
                    if (validTokens.remove(token, session)) {
                        GridAccessSessions.invalidate(session.principal);
                    }
                    return AuthCheckResult.UNAUTHENTICATED; // Token expired
                }
            } else {
                return AuthCheckResult.UNAUTHENTICATED; // Invalid token
            }
        }

        List<String> cookies = t.getRequestHeaders()
            .get("Cookie");
        if (cookies != null && !cookies.isEmpty()) {
            String cookiesString = cookies.get(0);
            for (String cookie : cookiesString.split("; ")) {
                if (cookie.startsWith("authenticationToken=")) {
                    String token = cookie.substring("authenticationToken=".length());
                    AuthSession session = validTokens.get(token);
                    if (session != null) {
                        long validity = session.expiresAtMillis;
                        if (System.currentTimeMillis() < validity) {
                            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                                t.getRequestURI()
                                    .getQuery());
                            if (GET_PARAMS.containsKey("logout")) {
                                validTokens.remove(token); // Invalidate token on logout
                                GridAccessSessions.invalidate(session.principal);
                                t.getResponseHeaders()
                                    .add("Set-Cookie", sessionCookie(token, -1));
                                t.getResponseHeaders()
                                    .add("Location", ".");
                                t.sendResponseHeaders(302, -1);
                                return AuthCheckResult.RESPONSE_SENT; // Logout successful
                            }
                            requestContext.set(new RequestContext(t, session.principal));
                            return AuthCheckResult.AUTHENTICATED; // Token is valid
                        } else {
                            if (validTokens.remove(token, session)) {
                                GridAccessSessions.invalidate(session.principal);
                            }
                            t.getResponseHeaders()
                                .add("Set-Cookie", sessionCookie(token, -1));
                            return AuthCheckResult.UNAUTHENTICATED; // Token expired
                        }
                    } else {
                        t.getResponseHeaders()
                            .add("Set-Cookie", sessionCookie(token, -1));
                        return AuthCheckResult.UNAUTHENTICATED; // Invalid token
                    }
                }
            }
        }
        if (t.getRequestMethod()
            .equals("POST")) {
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
                        .add("Location", "?" + registration.error);
                    t.sendResponseHeaders(302, -1);
                    return AuthCheckResult.RESPONSE_SENT;
                }

                String confirmationToken = generateToken(50);
                Pair<String, String> pending = Pair.of(confirmationToken, registration.passwordHash);
                if (!publishRegistration(requestLifecycleGeneration, registration.playerUuid, pending)) {
                    sendServerStopping(t);
                    return AuthCheckResult.RESPONSE_SENT;
                }
                t.getResponseHeaders()
                    .add("Location", "?confirmregistration&token=" + confirmationToken);
                t.sendResponseHeaders(302, -1);
                return AuthCheckResult.RESPONSE_SENT; // Registration initiated
            }

            if (postData.containsKey("password") && postData.containsKey("username")) {
                LoginResult login = authenticateLogin(postData.get("username"), postData.get("password"));
                if (!login.succeeded()) {
                    t.getResponseHeaders()
                        .add("Location", "?" + login.error);
                    t.sendResponseHeaders(302, -1);
                    return AuthCheckResult.RESPONSE_SENT;
                }
                boolean rememberMe = postData.containsKey("remember");
                String token = generateToken();
                long validFor = rememberMe ? 604_800L : 3600L; // 1 week or 1 hour
                AuthSession session = new AuthSession(System.currentTimeMillis() + validFor * 1000L, login.principal);
                if (!publishToken(requestLifecycleGeneration, token, session)) {
                    sendServerStopping(t);
                    return AuthCheckResult.RESPONSE_SENT;
                }
                t.getResponseHeaders()
                    .add("Set-Cookie", sessionCookie(token, validFor));
                t.getResponseHeaders()
                    .add("Location", ".");
                t.sendResponseHeaders(302, -1);
                return AuthCheckResult.RESPONSE_SENT;
            }
        }
        return AuthCheckResult.UNAUTHENTICATED;
    }

    private static boolean preHTTPHandler(HttpExchange t) throws IOException {
        InetAddress client = resolveClientAddress(t);
        if (!isAlreadyIdentified(t, client) && !rateLimiter.isAllowed(client)) {
            byte[] raw_response = "Too Many Requests".getBytes(StandardCharsets.UTF_8);
            t.getResponseHeaders()
                .add("Content-Type", "text/plain");
            t.sendResponseHeaders(429, raw_response.length); // Too Many Requests
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
            return true;
        }
        t.getResponseHeaders()
            .add("Access-Control-Allow-Origin", "*");
        if (t.getRequestMethod()
            .equalsIgnoreCase("OPTIONS")) {
            t.getResponseHeaders()
                .add("Access-Control-Allow-Methods", "GET, OPTIONS");
            t.getResponseHeaders()
                .add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            t.sendResponseHeaders(204, -1);
            return true;
        }
        AuthCheckResult authResult = checkAuth(t);
        if (authResult == AuthCheckResult.RESPONSE_SENT) {
            return true;
        }
        if (authResult == AuthCheckResult.UNAUTHENTICATED) {
            t.sendResponseHeaders(401, -1);
            return true;
        }
        return false;
    }

    private static String enqueueServerThreadTask(IServerThreadTask task) {
        if (!acceptingHTTPRequests) {
            task.failIfPending("SERVER_STOPPING");
            return "SERVER_STOPPING";
        }
        if (!requests.offer(task)) {
            String status = acceptingHTTPRequests ? "SERVER_BUSY" : "SERVER_STOPPING";
            task.failIfPending(status);
            return status;
        }
        if (!acceptingHTTPRequests && requests.remove(task)) {
            task.failIfPending("SERVER_STOPPING");
            return "SERVER_STOPPING";
        }
        return null;
    }

    private static UUID findOnlinePlayerOnServerThread(String username) throws ServerTaskUnavailableException {
        OnlinePlayerLookupTask task = new OnlinePlayerLookupTask(username);
        String unavailableStatus = enqueueServerThreadTask(task);
        if (unavailableStatus != null) {
            throw new ServerTaskUnavailableException(unavailableStatus);
        }
        try {
            return task.result.get(AUTH_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            requests.remove(task);
            task.failIfPending("SERVER_BUSY");
            throw new ServerTaskUnavailableException("SERVER_BUSY");
        } catch (InterruptedException e) {
            requests.remove(task);
            task.failIfPending("SERVER_STOPPING");
            Thread.currentThread()
                .interrupt();
            throw new ServerTaskUnavailableException("SERVER_STOPPING");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ServerTaskUnavailableException) {
                throw (ServerTaskUnavailableException) cause;
            }
            throw new ServerTaskUnavailableException("INTERNAL_ERROR");
        }
    }

    private static boolean sendRequest(ISyncedRequest request) {
        if (enqueueServerThreadTask(request) != null) {
            return true;
        }
        try {
            request.awaitCompletion(10L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            requests.remove(request);
            request.failIfPending("TIMEOUT");
        } catch (InterruptedException e) {
            requests.remove(request);
            request.failIfPending("SERVER_STOPPING");
            Thread.currentThread()
                .interrupt();
            return true;
        }
        return false;
    }

    static class SyncedRequestHandler implements HttpHandler {

        private final Constructor<? extends ISyncedRequest> factory;

        public SyncedRequestHandler(Class<? extends ISyncedRequest> syncedRequestClass) {
            try {
                factory = syncedRequestClass.getConstructor();
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            if (preHTTPHandler(t)) return;

            ISyncedRequest syncedRequest;

            try {
                syncedRequest = factory.newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }

            boolean serviceUnavailable = false;
            if (syncedRequest.init(requestContext.get())) {
                serviceUnavailable = sendRequest(syncedRequest);
            }

            byte[] raw_response = syncedRequest.getJSON()
                .getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(serviceUnavailable ? 503 : 200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();

        }

    }

    static class ASyncRequestHandler implements HttpHandler {

        private final Constructor<? extends IAsyncRequest> factory;

        public ASyncRequestHandler(Class<? extends IAsyncRequest> syncedRequestClass) {
            try {
                factory = syncedRequestClass.getConstructor();
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            if (preHTTPHandler(t)) return;

            IAsyncRequest asyncRequest;

            try {
                asyncRequest = factory.newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }

            asyncRequest.handle(requestContext.get());

            byte[] raw_response = asyncRequest.getJSON()
                .getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }

    }

    static class AuthHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {
            long requestLifecycleGeneration = httpLifecycleGeneration.get();
            InetAddress client = resolveClientAddress(t);
            if (!isAlreadyIdentified(t, client) && !rateLimiter.isAllowed(client)) {
                byte[] raw_response = "Too Many Requests".getBytes(StandardCharsets.UTF_8);
                t.getResponseHeaders()
                    .add("Content-Type", "text/plain");
                t.sendResponseHeaders(429, raw_response.length); // Too Many Requests
                OutputStream os = t.getResponseBody();
                os.write(raw_response);
                os.close();
                return;
            }
            if (t.getRequestMethod()
                .equals("POST")) {
                String postRaw = readBody(t);
                if (postRaw == null) {
                    byte[] raw_response = "requesttoolarge".getBytes(StandardCharsets.UTF_8);
                    t.sendResponseHeaders(400, raw_response.length);
                    OutputStream os = t.getResponseBody();
                    os.write(raw_response);
                    os.close();
                    return;
                }
                Map<String, String> postData = HTTPUtils.parseQueryString(postRaw);

                if (postData.containsKey("register") && postData.containsKey("password")) {
                    RegistrationResult registration = prepareRegistration(
                        postData.get("register"),
                        postData.get("password"));
                    if (!registration.succeeded() && registration.serviceUnavailable) {
                        sendServerUnavailable(t, registration.error);
                        return;
                    }
                    if (!registration.succeeded()) {
                        byte[] raw_response = registration.error.getBytes(StandardCharsets.UTF_8);
                        t.sendResponseHeaders(400, raw_response.length);
                        OutputStream os = t.getResponseBody();
                        os.write(raw_response);
                        os.close();
                        return;
                    }

                    String confirmationToken = generateToken(50);
                    Pair<String, String> pending = Pair.of(confirmationToken, registration.passwordHash);
                    if (!publishRegistration(requestLifecycleGeneration, registration.playerUuid, pending)) {
                        sendServerStopping(t);
                        return;
                    }
                    byte[] raw_response = confirmationToken.getBytes(StandardCharsets.UTF_8);
                    t.sendResponseHeaders(200, raw_response.length);
                    OutputStream os = t.getResponseBody();
                    os.write(raw_response);
                    os.close();
                    return;
                }

                if (postData.containsKey("password") && postData.containsKey("username")) {
                    LoginResult login = authenticateLogin(postData.get("username"), postData.get("password"));
                    if (!login.succeeded()) {
                        byte[] raw_response = login.error.getBytes(StandardCharsets.UTF_8);
                        t.sendResponseHeaders(400, raw_response.length);
                        OutputStream os = t.getResponseBody();
                        os.write(raw_response);
                        os.close();
                        return;
                    }
                    boolean rememberMe = postData.containsKey("remember");
                    String token = generateToken();
                    long validFor = rememberMe ? 604_800L : 3600L; // 1 week or 1 hour
                    AuthSession session = new AuthSession(
                        System.currentTimeMillis() + validFor * 1000L,
                        login.principal);
                    if (!publishToken(requestLifecycleGeneration, token, session)) {
                        sendServerStopping(t);
                        return;
                    }
                    JsonObject json = new JsonObject();
                    json.addProperty("token", token);
                    json.addProperty("username", login.principal.getUsername());
                    json.addProperty("isAdmin", login.principal.isAdmin());
                    json.addProperty("isOutdated", Config.CHECK_FOR_UPDATES() && VersionChecker.isOutdated());
                    byte[] raw_response = json.toString()
                        .getBytes(StandardCharsets.UTF_8);
                    t.sendResponseHeaders(200, raw_response.length);
                    OutputStream os = t.getResponseBody();
                    os.write(raw_response);
                    os.close();
                    return;
                }
            }

            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                t.getRequestURI()
                    .getQuery());

            if (GET_PARAMS.containsKey("revoke")) {
                List<String> auth = t.getRequestHeaders()
                    .get("Authorization");
                if (auth != null && !auth.isEmpty()) {
                    String token = auth.get(0);
                    token = token.replace("Bearer ", "");
                    AuthSession revoked = validTokens.remove(token);
                    if (revoked != null) {
                        GridAccessSessions.invalidate(revoked.principal);
                    }
                    t.sendResponseHeaders(200, -1);
                    return;
                }
            }

            t.sendResponseHeaders(400, -1);
        }

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
        sendServerUnavailable(exchange, "SERVER_STOPPING");
    }

    private static void sendServerUnavailable(HttpExchange exchange, String status) throws IOException {
        byte[] response = status.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(503, response.length);
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
                t.sendResponseHeaders(429, raw_response.length); // Too Many Requests
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
                    is.read(raw_response);
                    t.sendResponseHeaders(200, raw_response.length);
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
                t.sendResponseHeaders(404, raw_response.length);
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
                Config.CHECK_FOR_UPDATES() && VersionChecker.isOutdated() ? "true" : "false");
            RequestContext context = requestContext.get();
            if (context != null) {
                response = response.replace("_REPLACE_ME_USERNAME", context.principal.getUsername());
                response = response.replace("_REPLACE_ME_IS_ADMIN", context.isAdmin() ? "true" : "false");
            }
            byte[] raw_response = response.getBytes(StandardCharsets.UTF_8);
            t.getResponseHeaders()
                .set("Content-Type", "text/html; charset=UTF-8");
            t.sendResponseHeaders(200, raw_response.length);
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
