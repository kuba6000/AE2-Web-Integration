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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;
import pl.kuba6000.ae2webintegration.core.utils.RateLimiter;
import pl.kuba6000.ae2webintegration.core.utils.VersionChecker;

public class AE2Controller {

    public static IAE AE2Interface;
    public static IServerPlatform serverPlatform;

    public static long timer;
    private static HttpServer server;

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
        // -1 id is admin permissions -2 is localhost access
        private final int userID;
        private final String username;

        public RequestContext(HttpExchange exchange, int userID) {
            this.exchange = exchange;
            this.getParams = HTTPUtils.parseQueryString(
                exchange.getRequestURI()
                    .getQuery());
            this.userID = userID;
            if (userID == -1) {
                this.username = "admin";
            } else if (userID == -2) {
                this.username = "localhost";
            } else {
                PlayerIdentity profile = AE2Controller.AE2Interface.web$getPlayerData()
                    .web$getPlayerProfile(userID);
                this.username = profile != null ? profile.name : "unknown";
            }
        }

        public HttpExchange getExchange() {
            return exchange;
        }

        public Map<String, String> getGetParams() {
            return getParams;
        }

        public int getUserID() {
            return userID;
        }

        public boolean isAdmin() {
            return userID == -1 || userID == -2;
        }
    }

    static ThreadLocal<RequestContext> requestContext = new ThreadLocal<>();

    public static ConcurrentHashMap<UUID, Pair<String, String>> awaitingRegistration = new ConcurrentHashMap<>();

    public static ConcurrentLinkedQueue<ISyncedRequest> requests = new ConcurrentLinkedQueue<>();

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
        Pair<Long, Integer> tokenData = validTokens.get(token);
        return tokenData != null && System.currentTimeMillis() < tokenData.getLeft();
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
        rateLimiter = new RateLimiter(Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE(), 60 * 1000);
        clientAddressResolver = ClientAddressResolver.fromConfig(Config.TRUSTED_PROXIES());
        try {
            server = HttpServer.create(new InetSocketAddress(Config.AE_PORT()), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.createContext("/grids", new SyncedRequestHandler(GetGridList.class));
        server.createContext("/list", new SyncedRequestHandler(GetCPUList.class));
        server.createContext("/get", new SyncedRequestHandler(GetCPU.class));
        server.createContext("/cancelcpu", new SyncedRequestHandler(CancelCPU.class));
        server.createContext("/items", new SyncedRequestHandler(GetItems.class));
        server.createContext("/order", new SyncedRequestHandler(Order.class));
        server.createContext("/job", new SyncedRequestHandler(Job.class));
        server.createContext("/trackinghistory", new ASyncRequestHandler(GetTrackingHistory.class));
        server.createContext("/gettracking", new ASyncRequestHandler(GetTracking.class));
        server.createContext("/gridsettings", new ASyncRequestHandler(GridSettings.class));
        server.createContext("/auth", new AuthHandler());
        server.createContext("/", new WebHandler());
        server.setExecutor(serverThread);
        server.start();
    }

    public static void stopHTTPServer() {
        server.stop(0);
    }

    private static final ExecutorService serverThread = new ThreadPoolExecutor(
        0,
        Integer.MAX_VALUE,
        60L,
        TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>()) {

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            requestContext.remove();
        }
    };

    public static ConcurrentHashMap<Integer, IAEGenericStack> hashcodeToStack = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Pair<Long, Integer>> validTokens = new ConcurrentHashMap<>();

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

    private static boolean checkAuth(HttpExchange t) throws IOException {
        InetAddress client = resolveClientAddress(t);

        if (Config.ALLOW_NO_PASSWORD_ON_LOCALHOST() && client.isLoopbackAddress()) {
            requestContext.set(new RequestContext(t, -2)); // Localhost access
            return true;
        }

        // Alternative authentication method
        List<String> auth = t.getRequestHeaders()
            .get("Authorization");
        if (auth != null && !auth.isEmpty()) {
            String token = auth.get(0);
            token = token.replace("Bearer ", "");
            Pair<Long, Integer> tokenData = validTokens.get(token);
            if (tokenData != null) {
                long validity = tokenData.getLeft();
                if (System.currentTimeMillis() < validity) {
                    requestContext.set(new RequestContext(t, tokenData.getRight()));
                    return true; // Token is valid
                } else {
                    validTokens.remove(token); // Remove expired token
                    return false; // Token expired
                }
            } else {
                return false; // Invalid token
            }
        }

        List<String> cookies = t.getRequestHeaders()
            .get("Cookie");
        if (cookies != null && !cookies.isEmpty()) {
            String cookiesString = cookies.get(0);
            for (String cookie : cookiesString.split("; ")) {
                if (cookie.startsWith("authenticationToken=")) {
                    String token = cookie.substring("authenticationToken=".length());
                    Pair<Long, Integer> tokenData = validTokens.get(token);
                    if (tokenData != null) {
                        long validity = tokenData.getLeft();
                        if (System.currentTimeMillis() < validity) {
                            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                                t.getRequestURI()
                                    .getQuery());
                            if (GET_PARAMS.containsKey("logout")) {
                                validTokens.remove(token); // Invalidate token on logout
                                GridAccessSessions.invalidate(tokenData.getRight());
                                t.getResponseHeaders()
                                    .add("Set-Cookie", sessionCookie(token, -1));
                                t.getResponseHeaders()
                                    .add("Location", ".");
                                t.sendResponseHeaders(302, -1);
                                return false; // Logout successful
                            }
                            requestContext.set(new RequestContext(t, tokenData.getRight()));
                            return true; // Token is valid
                        } else {
                            validTokens.remove(token); // Remove expired token
                            t.getResponseHeaders()
                                .add("Set-Cookie", sessionCookie(token, -1));
                            return false; // Token expired
                        }
                    } else {
                        t.getResponseHeaders()
                            .add("Set-Cookie", sessionCookie(token, -1));
                        return false; // Invalid token
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
                String username = postData.get("register");
                UUID uuid = serverPlatform.getOnlinePlayerUUID(username);
                if (uuid == null) {
                    t.getResponseHeaders()
                        .add("Location", "?notonline");
                    t.sendResponseHeaders(302, -1);
                    return false;
                }
                String password = postData.get("password");
                try {
                    password = PasswordHelper.generateStrongPasswordHash(password);
                } catch (Exception e) {
                    t.getResponseHeaders()
                        .add("Location", "?invalidpassword");
                    t.sendResponseHeaders(302, -1);
                    return false;
                }

                String confirmationToken = generateToken(50);
                awaitingRegistration.put(uuid, Pair.of(confirmationToken, password));
                t.getResponseHeaders()
                    .add("Location", "?confirmregistration&token=" + confirmationToken);
                t.sendResponseHeaders(302, -1);
                return false; // Registration initiated
            }

            if (postData.containsKey("password") && postData.containsKey("username")) {
                String username = postData.get("username");
                int playerID;
                if (username.equalsIgnoreCase("admin") || !Config.AE_PUBLIC_MODE()) {
                    username = "Admin";
                    playerID = -1;
                    String password = postData.get("password");
                    if (!password.equals(Config.AE_PASSWORD()) && !Config.AE_PASSWORD()
                        .isEmpty()) {
                        t.getResponseHeaders()
                            .add("Location", "?invalidpassword");
                        t.sendResponseHeaders(302, -1);
                        return false;
                    }
                } else {
                    playerID = CoreData.getPlayerId(username);
                    if (playerID == -1) {
                        t.getResponseHeaders()
                            .add("Location", "?invaliduser");
                        t.sendResponseHeaders(302, -1);
                        return false;
                    }
                    String password = postData.get("password");
                    if (!CoreData.verifyPassword(playerID, password)) {
                        t.getResponseHeaders()
                            .add("Location", "?invalidpassword");
                        t.sendResponseHeaders(302, -1);
                        return false;
                    }
                }
                boolean rememberMe = postData.containsKey("remember");
                String token = generateToken();
                long validFor = rememberMe ? 604_800L : 3600L; // 1 week or 1 hour
                validTokens.put(token, Pair.of(System.currentTimeMillis() + validFor * 1000L, playerID)); // 1 hour
                                                                                                          // validity
                t.getResponseHeaders()
                    .add("Set-Cookie", sessionCookie(token, validFor));
                t.getResponseHeaders()
                    .add("Location", ".");
                t.sendResponseHeaders(302, -1);
                return true;
            }
        }
        return false;
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
        if (!checkAuth(t)) {
            t.sendResponseHeaders(401, -1);
            return true;
        }
        return false;
    }

    private static boolean sendRequest(ISyncedRequest request) {
        requests.offer(request);
        int timeout = 0;
        while (!request.isDone.get() && timeout < 50) {
            try {
                Thread.sleep(200);
                timeout++;
            } catch (InterruptedException e) {
                return requests.remove(request);
            }
        }
        if (timeout == 50) {
            return requests.remove(request);
        }
        return true;
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

            if (syncedRequest.init(requestContext.get())) {
                sendRequest(syncedRequest);
            }

            byte[] raw_response = syncedRequest.getJSON()
                .getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(200, raw_response.length);
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
                    String username = postData.get("register");
                    UUID uuid = serverPlatform.getOnlinePlayerUUID(username);
                    if (uuid == null) {
                        byte[] raw_response = "notonline".getBytes(StandardCharsets.UTF_8);
                        t.sendResponseHeaders(400, raw_response.length);
                        OutputStream os = t.getResponseBody();
                        os.write(raw_response);
                        os.close();
                        return;
                    }
                    String password = postData.get("password");
                    try {
                        password = PasswordHelper.generateStrongPasswordHash(password);
                    } catch (Exception e) {
                        byte[] raw_response = "invalidpassword".getBytes(StandardCharsets.UTF_8);
                        t.sendResponseHeaders(400, raw_response.length);
                        OutputStream os = t.getResponseBody();
                        os.write(raw_response);
                        os.close();
                        return;
                    }

                    String confirmationToken = generateToken(50);
                    awaitingRegistration.put(uuid, Pair.of(confirmationToken, password));
                    byte[] raw_response = confirmationToken.getBytes(StandardCharsets.UTF_8);
                    t.sendResponseHeaders(200, raw_response.length);
                    OutputStream os = t.getResponseBody();
                    os.write(raw_response);
                    os.close();
                    return;
                }

                if (postData.containsKey("password") && postData.containsKey("username")) {
                    String username = postData.get("username");
                    int playerID;
                    if (username.equalsIgnoreCase("admin") || !Config.AE_PUBLIC_MODE()) {
                        username = "Admin";
                        playerID = -1;
                        String password = postData.get("password");
                        if (!password.equals(Config.AE_PASSWORD()) && !Config.AE_PASSWORD()
                            .isEmpty()) {
                            byte[] raw_response = "invalidpassword".getBytes(StandardCharsets.UTF_8);
                            t.sendResponseHeaders(400, raw_response.length);
                            OutputStream os = t.getResponseBody();
                            os.write(raw_response);
                            os.close();
                            return;
                        }
                    } else {
                        playerID = CoreData.getPlayerId(username);
                        if (playerID == -1) {
                            byte[] raw_response = "invaliduser".getBytes(StandardCharsets.UTF_8);
                            t.sendResponseHeaders(400, raw_response.length);
                            OutputStream os = t.getResponseBody();
                            os.write(raw_response);
                            os.close();
                            return;
                        }
                        String password = postData.get("password");
                        if (!CoreData.verifyPassword(playerID, password)) {
                            byte[] raw_response = "invalidpassword".getBytes(StandardCharsets.UTF_8);
                            t.sendResponseHeaders(400, raw_response.length);
                            OutputStream os = t.getResponseBody();
                            os.write(raw_response);
                            os.close();
                            return;
                        }
                    }
                    boolean rememberMe = postData.containsKey("remember");
                    String token = generateToken();
                    long validFor = rememberMe ? 604_800L : 3600L; // 1 week or 1 hour
                    validTokens.put(token, Pair.of(System.currentTimeMillis() + validFor * 1000L, playerID)); // 1 hour
                                                                                                              // validity
                    JsonObject json = new JsonObject();
                    json.addProperty("token", token);
                    json.addProperty("username", username);
                    json.addProperty("isAdmin", playerID == -1);
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
                    Pair<Long, Integer> revoked = validTokens.remove(token);
                    if (revoked != null) {
                        GridAccessSessions.invalidate(revoked.getRight());
                    }
                    t.sendResponseHeaders(200, -1);
                    return;
                }
            }

            t.sendResponseHeaders(400, -1);
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

            if (!checkAuth(t)) {
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
                response = response.replace("_REPLACE_ME_USERNAME", context.username);
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
