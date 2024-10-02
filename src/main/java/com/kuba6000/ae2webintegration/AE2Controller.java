package com.kuba6000.ae2webintegration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;

import com.kuba6000.ae2webintegration.ae2request.async.GetTracking;
import com.kuba6000.ae2webintegration.ae2request.async.GetTrackingHistory;
import com.kuba6000.ae2webintegration.ae2request.async.IAsyncRequest;
import com.kuba6000.ae2webintegration.ae2request.sync.CancelCPU;
import com.kuba6000.ae2webintegration.ae2request.sync.GetCPU;
import com.kuba6000.ae2webintegration.ae2request.sync.GetCPUList;
import com.kuba6000.ae2webintegration.ae2request.sync.GetItems;
import com.kuba6000.ae2webintegration.ae2request.sync.ISyncedRequest;
import com.kuba6000.ae2webintegration.ae2request.sync.Job;
import com.kuba6000.ae2webintegration.ae2request.sync.Order;
import com.kuba6000.ae2webintegration.utils.HTTPUtils;
import com.kuba6000.ae2webintegration.utils.VersionChecker;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import appeng.api.AEApi;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cache.SecurityCache;

public class AE2Controller {

    public static Grid activeGrid;
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
        server.createContext("/", new WebHandler());
        server.setExecutor(serverThread);
        server.start();
    }

    public static void stopHTTPServer() {
        server.stop(0);
    }

    private static final ExecutorService serverThread = Executors.newCachedThreadPool();

    public static IItemList<IAEItemStack> globalItemList = AEApi.instance()
        .storage()
        .createItemList();

    public static ConcurrentHashMap<Integer, IAEItemStack> hashcodeToAEItemStack = new ConcurrentHashMap<>();

    public static int nextJobID = 1;

    public static int getNextJobID() {
        return nextJobID++;
    }

    public static HashMap<Integer, Future<ICraftingJob>> jobs = new HashMap<>();

    private static boolean checkAuth(HttpExchange t) {
        if (Config.ALLOW_NO_PASSWORD_ON_LOCALHOST && t.getRemoteAddress()
            .getAddress()
            .isLoopbackAddress()) {
            return true;
        }
        List<String> auth = t.getRequestHeaders()
            .get("Authorization");
        if (auth == null || auth.isEmpty()) return false;
        String token = auth.get(0);
        token = token.replace("Basic ", "");
        try {
            String[] user_pass = new String(
                Base64.getDecoder()
                    .decode(token),
                "UTF-8").split(":");
            if (user_pass.length < 2) return false;
            String user = user_pass[0];
            String password = user_pass[1];
            // we dont really care about the user :P
            if (password.equals(Config.AE_PASSWORD)) return true;
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return false;
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
            t.getResponseHeaders()
                .add("WWW-Authenticate", "Basic realm=\"AE2 Panel, please login\"");
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

    static class WebHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {

            if (!checkAuth(t)) {
                t.getResponseHeaders()
                    .add("WWW-Authenticate", "Basic realm=\"AE2 Panel, please login\"");
                t.sendResponseHeaders(401, -1);
                return;
            }

            // only accept index file
            String path = t.getRequestURI()
                .getPath();
            if (!path.equals("/") && !path.isEmpty()
                && !path.equals("/index.php")
                && !path.equals("/index.html")
                && !path.equals("/index.htm")
                && !path.equals("/index.asp")
                && !path.equals("/index.aspx")
                && !path.equals("/index.jsp")) {

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

                String response = "<h1>Invalid url! (ERROR 404)</h1>";
                byte[] raw_response = response.getBytes();
                t.sendResponseHeaders(404, raw_response.length);
                OutputStream os = t.getResponseBody();
                os.write(raw_response);
                os.close();
                return;
            }

            String response;
            try (InputStream is = AE2Controller.class.getResourceAsStream("/assets/webpage.html")) {
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

    public static boolean tryValidateOrVerify(Grid testGrid, CraftingGridCache craftingGrid) {
        if (isValid()) return testGrid == activeGrid;
        else {
            if (craftingGrid == null) craftingGrid = testGrid.getCache(ICraftingGrid.class);
            if (craftingGrid.getCpus()
                .size() >= Config.AE_CPUS_THRESHOLD) {
                activeGrid = testGrid;
                return true;
            }
        }
        return false;
    }

    public static boolean tryValidate() {
        for (Grid grid : TickHandler.INSTANCE.getGridList()) {
            IPathingGrid pathingGrid = grid.getCache(IPathingGrid.class);
            if (pathingGrid != null && !pathingGrid.isNetworkBooting()
                && pathingGrid.getControllerState() == ControllerState.CONTROLLER_ONLINE) {
                ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
                if (craftingGrid != null) {
                    if ((long) craftingGrid.getCpus()
                        .size() >= Config.AE_CPUS_THRESHOLD) {
                        AE2Controller.activeGrid = grid;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isValid() {
        if (activeGrid == null) return false;
        if (activeGrid.isEmpty()) {
            activeGrid = null;
            return false;
        }
        IPathingGrid pathingGrid = activeGrid.getCache(IPathingGrid.class);
        if (pathingGrid == null || pathingGrid.isNetworkBooting()
            || pathingGrid.getControllerState() != ControllerState.CONTROLLER_ONLINE) {
            activeGrid = null;
            return false;
        }
        return true;
    }

    public static void init() {
        try {
            startHTTPServer();
            SecurityCache.registerOpPlayer(AEControllerProfile);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

}
