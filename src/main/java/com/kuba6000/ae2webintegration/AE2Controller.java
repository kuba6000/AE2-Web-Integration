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
import java.util.ArrayList;
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

import com.kuba6000.ae2webintegration.ae2sync.CancelCPU;
import com.kuba6000.ae2webintegration.ae2sync.GetCPU;
import com.kuba6000.ae2webintegration.ae2sync.GetCPUList;
import com.kuba6000.ae2webintegration.ae2sync.GetItems;
import com.kuba6000.ae2webintegration.ae2sync.ISyncedRequest;
import com.kuba6000.ae2webintegration.ae2sync.Job;
import com.kuba6000.ae2webintegration.ae2sync.Order;
import com.kuba6000.ae2webintegration.utils.GSONUtils;
import com.kuba6000.ae2webintegration.utils.HTTPUtils;
import com.kuba6000.ae2webintegration.utils.VersionChecker;
import com.mojang.authlib.GameProfile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import appeng.api.AEApi;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.Grid;
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
        server.createContext("/trackinghistory", new TrackingHistoryHandler());
        server.createContext("/gettracking", new GetTrackingHandler());
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

    static class TrackingHistoryHandler implements HttpHandler {

        static class TrackingHistoryElement {

            public long timeStarted;
            public long timeDone;
            public boolean wasCancelled;
            public IAEItemStack finalOutput;
            public int id;
        }

        @Override
        public void handle(HttpExchange t) throws IOException {

            if (preHTTPHandler(t)) return;

            ArrayList<TrackingHistoryElement> jobs = new ArrayList<>(AE2JobTracker.trackingInfos.size());

            for (Map.Entry<Integer, AE2JobTracker.JobTrackingInfo> integerJobTrackingInfoEntry : AE2JobTracker.trackingInfos
                .entrySet()) {
                TrackingHistoryElement element = new TrackingHistoryElement();
                element.id = integerJobTrackingInfoEntry.getKey();
                element.timeStarted = integerJobTrackingInfoEntry.getValue().timeStarted;
                element.timeDone = integerJobTrackingInfoEntry.getValue().timeDone;
                element.wasCancelled = integerJobTrackingInfoEntry.getValue().wasCancelled;
                element.finalOutput = integerJobTrackingInfoEntry.getValue().finalOutput;
                jobs.add(element);
            }

            jobs.sort((i1, i2) -> Long.compare(i2.timeDone, i1.timeDone));

            String response = GSONUtils.GSON_BUILDER.create()
                .toJson(jobs);
            byte[] raw_response = response.getBytes();
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }
    }

    static class GetTrackingHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {

            if (preHTTPHandler(t)) return;

            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                t.getRequestURI()
                    .getQuery());
            if (!GET_PARAMS.containsKey("id")) {
                return;
            }
            int id = Integer.parseInt(GET_PARAMS.get("id"));

            AE2JobTracker.JobTrackingInfo info = AE2JobTracker.trackingInfos.get(id);
            if (info == null) return;

            String response = GSONUtils.GSON_BUILDER.create()
                .toJson(info);
            byte[] raw_response = response.getBytes();
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
