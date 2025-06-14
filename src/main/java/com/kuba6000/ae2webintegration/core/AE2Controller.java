package com.kuba6000.ae2webintegration.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;

import com.kuba6000.ae2webintegration.core.ae2request.async.GetTracking;
import com.kuba6000.ae2webintegration.core.ae2request.async.GetTrackingHistory;
import com.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import com.kuba6000.ae2webintegration.core.ae2request.sync.CancelCPU;
import com.kuba6000.ae2webintegration.core.ae2request.sync.GetCPU;
import com.kuba6000.ae2webintegration.core.ae2request.sync.GetCPUList;
import com.kuba6000.ae2webintegration.core.ae2request.sync.GetItems;
import com.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import com.kuba6000.ae2webintegration.core.ae2request.sync.Job;
import com.kuba6000.ae2webintegration.core.ae2request.sync.Order;
import com.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import com.kuba6000.ae2webintegration.core.interfaces.IAE;
import com.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.utils.HTTPUtils;
import com.kuba6000.ae2webintegration.core.utils.VersionChecker;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class AE2Controller {

    public static IAE AE2Interface;

    public static IAEGrid activeGrid;
    public static long timer;
    private static HttpServer server;

    public static GameProfile AEControllerProfile;

    static {
        try {
            AEControllerProfile = new GameProfile(
                UUID.nameUUIDFromBytes("AE2-WEB-INTEGRATION-AE2CONTROLLER".getBytes("UTF-8")),
                "AE2CONTROLLER");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static ConcurrentLinkedQueue<ISyncedRequest> requests = new ConcurrentLinkedQueue<>();

    public static void startHTTPServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(Config.AE_PORT), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.createContext("/list", new SyncedRequestHandler(GetCPUList.class));
        server.createContext("/get", new SyncedRequestHandler(GetCPU.class));
        server.createContext("/cancelcpu", new SyncedRequestHandler(CancelCPU.class));
        server.createContext("/items", new SyncedRequestHandler(GetItems.class));
        server.createContext("/order", new SyncedRequestHandler(Order.class));
        server.createContext("/job", new SyncedRequestHandler(Job.class));
        server.createContext("/trackinghistory", new ASyncRequestHandler(GetTrackingHistory.class));
        server.createContext("/gettracking", new ASyncRequestHandler(GetTracking.class));
        server.createContext("/auth", new AuthHandler());
        server.createContext("/", new WebHandler());
        server.setExecutor(serverThread);
        server.start();
    }

    public static void stopHTTPServer() {
        server.stop(0);
    }

    private static final ExecutorService serverThread = Executors.newCachedThreadPool();

    public static ConcurrentHashMap<Integer, IItemStack> hashcodeToAEItemStack = new ConcurrentHashMap<>();

    public static int nextJobID = 1;

    public static int getNextJobID() {
        return nextJobID++;
    }

    public static HashMap<Integer, Future<IAECraftingJob>> jobs = new HashMap<>();

    private static final HashMap<String, Long> validTokens = new HashMap<>();

    private static String generateToken() {
        return new SecureRandom().ints(48, 122 + 1)
            .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
            .limit(200)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }

    private static boolean checkAuth(HttpExchange t) throws IOException {
        if (Config.ALLOW_NO_PASSWORD_ON_LOCALHOST && t.getRemoteAddress()
            .getAddress()
            .isLoopbackAddress()) {
            return true;
        }

        // Alternative authentication method
        List<String> auth = t.getRequestHeaders()
            .get("Authorization");
        if (auth != null && !auth.isEmpty()) {
            String token = auth.get(0);
            token = token.replace("Bearer ", "");
            if (validTokens.containsKey(token)) {
                long validity = validTokens.get(token);
                if (System.currentTimeMillis() < validity) {
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
                    if (validTokens.containsKey(token)) {
                        long validity = validTokens.get(token);
                        if (System.currentTimeMillis() < validity) {
                            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                                t.getRequestURI()
                                    .getQuery());
                            if (GET_PARAMS.containsKey("logout")) {
                                validTokens.remove(token); // Invalidate token on logout
                                t.getResponseHeaders()
                                    .add("Set-Cookie", "authenticationToken=" + token + "; Max-Age=-1; HttpOnly");
                                t.getResponseHeaders()
                                    .add("Location", ".");
                                t.sendResponseHeaders(302, -1);
                                return false; // Logout successful
                            }
                            return true; // Token is valid
                        } else {
                            validTokens.remove(token); // Remove expired token
                            t.getResponseHeaders()
                                .add("Set-Cookie", "authenticationToken=" + token + "; Max-Age=-1; HttpOnly");
                            return false; // Token expired
                        }
                    } else {
                        t.getResponseHeaders()
                            .add("Set-Cookie", "authenticationToken=" + token + "; Max-Age=-1; HttpOnly");
                        return false; // Invalid token
                    }
                }
            }
        }
        if (t.getRequestMethod()
            .equals("POST")) {
            String postRaw = new Scanner(t.getRequestBody()).nextLine();
            Map<String, String> postData = HTTPUtils.parseQueryString(postRaw);

            if (postData.containsKey("password")) {
                String password = postData.get("password");
                boolean rememberMe = postData.containsKey("remember");
                if (password.equals(Config.AE_PASSWORD)) {
                    String token = generateToken();
                    long validFor = rememberMe ? 604_800L : 3600L; // 1 week or 1 hour
                    validTokens.put(token, System.currentTimeMillis() + validFor * 1000L); // 1 hour validity
                    t.getResponseHeaders()
                        .add("Set-Cookie", "authenticationToken=" + token + "; Max-Age=" + validFor + "; HttpOnly");
                    t.getResponseHeaders()
                        .add("Location", ".");
                    t.sendResponseHeaders(302, -1);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean preHTTPHandler(HttpExchange t) throws IOException {
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

            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                t.getRequestURI()
                    .getQuery());

            ISyncedRequest syncedRequest;

            try {
                syncedRequest = factory.newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }

            if (syncedRequest.init(GET_PARAMS)) {
                sendRequest(syncedRequest);
            }

            byte[] raw_response = syncedRequest.getJSON()
                .getBytes();
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

            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                t.getRequestURI()
                    .getQuery());

            IAsyncRequest syncedRequest;

            try {
                syncedRequest = factory.newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }

            syncedRequest.handle(GET_PARAMS);

            byte[] raw_response = syncedRequest.getJSON()
                .getBytes();
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }

    }

    static class AuthHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {
            if (t.getRequestMethod()
                .equals("POST")) {
                String postRaw = new Scanner(t.getRequestBody()).nextLine();
                Map<String, String> postData = HTTPUtils.parseQueryString(postRaw);

                if (postData.containsKey("password")) {
                    String password = postData.get("password");
                    boolean rememberMe = postData.containsKey("remember");
                    if (password.equals(Config.AE_PASSWORD)) {
                        String token = generateToken();
                        long validFor = rememberMe ? 604_800L : 3600L; // 1 week or 1 hour
                        validTokens.put(token, System.currentTimeMillis() + validFor * 1000L); // 1 hour validity
                        byte[] raw_response = token.getBytes();
                        t.sendResponseHeaders(200, raw_response.length);
                        OutputStream os = t.getResponseBody();
                        os.write(raw_response);
                        os.close();
                        return;
                    }
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
                    validTokens.remove(token);
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
                byte[] raw_response = response.getBytes();
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
                try (InputStreamReader isr = new InputStreamReader(is);
                    BufferedReader reader = new BufferedReader(isr)) {
                    response = reader.lines()
                        .collect(Collectors.joining(System.lineSeparator()));
                }
            }
            response = response.replace("_REPLACE_ME_VERSION_OUTDATED", VersionChecker.isOutdated() ? "true" : "false");
            byte[] raw_response = response.getBytes();
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }

    }

    private static void setActiveGrid(IAEGrid grid) {
        activeGrid = grid;
    }

    public static boolean tryValidateOrVerify(IAEGrid testGrid, IAECraftingGrid craftingGrid) {
        if (isValid()) return activeGrid == testGrid;
        else {
            if (craftingGrid == null) craftingGrid = testGrid.web$getCraftingGrid();
            if (craftingGrid.web$getCPUCount() >= Config.AE_CPUS_THRESHOLD) {
                setActiveGrid(testGrid);
                return true;
            }
        }
        return false;
    }

    public static boolean tryValidate() {
        for (IAEGrid grid : AE2Interface.web$getGrids()) {
            IAEPathingGrid pathingGrid = grid.web$getPathingGrid();
            if (pathingGrid != null && !pathingGrid.web$isNetworkBooting()
                && pathingGrid.web$getControllerState() == AEControllerState.CONTROLLER_ONLINE) {
                IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
                if (craftingGrid != null) {
                    if ((long) craftingGrid.web$getCPUCount() >= Config.AE_CPUS_THRESHOLD) {
                        setActiveGrid(grid);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isValid() {
        if (activeGrid == null) return false;
        if (activeGrid.web$isEmpty()) {
            setActiveGrid(null);
            return false;
        }
        IAEPathingGrid pathingGrid = activeGrid.web$getPathingGrid();
        if (pathingGrid == null || pathingGrid.web$isNetworkBooting()
            || pathingGrid.web$getControllerState() != AEControllerState.CONTROLLER_ONLINE) {
            setActiveGrid(null);
            return false;
        }
        return true;
    }

    public static void init() {
        try {
            startHTTPServer();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

}
