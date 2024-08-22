package com.kuba6000.ae2webintegration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

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
import cpw.mods.fml.common.registry.GameRegistry;

public class AE2Controller {

    public static Grid activeGrid;
    public static long timer;
    private static HttpServer server;
    public static ConcurrentHashMap<REQUEST_OPERATION, AE2Data> updates = new ConcurrentHashMap<>();

    static GameProfile AEControllerProfile;

    static {
        try {
            AEControllerProfile = new GameProfile(
                UUID.nameUUIDFromBytes("AE2-WEB-INTEGRATION-AE2CONTROLLER".getBytes("UTF-8")),
                "AE2CONTROLLER");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static class REQUEST_OPERATION {

    }

    public static class LIST_CPUS extends REQUEST_OPERATION {

    }

    public static class GET_CPU extends REQUEST_OPERATION {

        int hashcode;

        public GET_CPU(String CPU_NAME) {
            hashcode = CPU_NAME.hashCode();
        }
    }

    public static class CANCEL_CPU extends REQUEST_OPERATION {

        int hashcode;

        public CANCEL_CPU(String CPU_NAME) {
            hashcode = CPU_NAME.hashCode();
        }

    }

    public static class GET_ITEMS extends REQUEST_OPERATION {

    }

    public static class BEGIN_ORDER extends REQUEST_OPERATION {

        IAEItemStack toOrder;

        public BEGIN_ORDER(IAEItemStack stack) {
            toOrder = stack;
        }
    }

    public static class CHECK_ORDER extends REQUEST_OPERATION {

        int id;

        public CHECK_ORDER(int id) {
            this.id = id;
        }

    }

    public static class CANCEL_ORDER extends REQUEST_OPERATION {

        int id;

        public CANCEL_ORDER(int id) {
            this.id = id;
        }

    }

    public static class SUBMIT_ORDER extends REQUEST_OPERATION {

        int id;

        public SUBMIT_ORDER(int id) {
            this.id = id;
        }

    }

    public static class TRACKING_LIST extends REQUEST_OPERATION {

    }

    public static class GET_TRACKING extends REQUEST_OPERATION {

        int id;

        public GET_TRACKING(int id) {
            this.id = id;
        }
    }

    public static ConcurrentLinkedQueue<REQUEST_OPERATION> requests = new ConcurrentLinkedQueue<>();

    public static class AE2Data {

        boolean isValid = true;

        HashMap<String, ClusterData> clusters = new HashMap<>();
        ArrayList<GSONDetailedItem> items;
        Integer jobID = null;
        boolean jobIsDone = false;
        public JobData jobData = null;
        public String jobSubmissionFailureMessage = null;

        public static class JobData {

            public boolean isSimulating;
            public long bytesTotal;
            public ArrayList<GSONJobItem> plan;

            public static class GSONJobItem {

                public String itemid;
                public String itemname;
                public long stored;
                public long requested;
                public long missing;
                public long steps;
            }
        }

        public static ClusterData EMPTY_DATA = new ClusterData();

        public static class ClusterData {

            public IAEItemStack finalOutput = null;
            public IItemList<IAEItemStack> active = null;
            public IItemList<IAEItemStack> pending = null;
            public IItemList<IAEItemStack> storage = null;
            public AE2JobTracker.JobTrackingInfo trackingInfo = null;

            public void initItemLists() {
                active = AEApi.instance()
                    .storage()
                    .createItemList();
                pending = AEApi.instance()
                    .storage()
                    .createItemList();
                storage = AEApi.instance()
                    .storage()
                    .createItemList();
            }
        }

        public static class ClusterCompactedData {

            public GSONItem finalOutput;
            public ArrayList<CompactedItem> items;
            public boolean hasTrackingInfo = false;
            public long timeStarted = 0L;
        }

        public AE2Data invalid() {
            isValid = false;
            return this;
        }

    }

    public static AE2Data INVALID_DATA = new AE2Data().invalid();

    public static void startHTTPServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(Config.AE_PORT), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.createContext("/list", new ListHandler());
        server.createContext("/get", new GetHandler());
        server.createContext("/cancelcpu", new CancelCPUHandler());
        server.createContext("/items", new ItemsHandler());
        server.createContext("/order", new OrderHandler());
        server.createContext("/job", new JobHandler());
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

    public static class GSONDetailedItem {

        public int hashcode;
        public String itemid;
        public String itemname;
        public long quantity;
        public boolean craftable;
    }

    public static IItemList<IAEItemStack> globalItemList = AEApi.instance()
        .storage()
        .createItemList();

    public static ConcurrentHashMap<Integer, IAEItemStack> hashcodeToAEItemStack = new ConcurrentHashMap<>();

    public static int nextJobID = 1;

    public static int getNextJobID() {
        return nextJobID++;
    }

    public static HashMap<Integer, Future<ICraftingJob>> jobs = new HashMap<>();

    public static class GSONItem {

        public String itemid;
        public String itemname;
        public long quantity;

        public GSONItem(String itemid, String itemname, long quantity) {
            this.itemid = itemid;
            this.itemname = itemname;
            this.quantity = quantity;
        }
    }

    public static class CompactedItem {

        public final String itemid;
        public final String itemname;
        public long active = 0;
        public long pending = 0;
        public long stored = 0;
        public long timeSpentCrafting = 0;
        public long craftedTotal = 0;
        public double shareInCraftingTime = 0d;
        public double craftsPerSec = 0d;

        @GSONUtils.SkipGSON
        private int hashcode = 0;

        public CompactedItem(String itemid, String itemname) {
            this.itemid = itemid;
            this.itemname = itemname;
        }

        public static CompactedItem create(IAEItemStack stack) {
            return new AE2Controller.CompactedItem(
                GameRegistry.findUniqueIdentifierFor(stack.getItem())
                    .toString() + ":"
                    + stack.getItemDamage(),
                stack.getItemStack()
                    .getDisplayName());
        }

        @Override
        public int hashCode() {
            if (hashcode == 0) {
                hashcode = Objects.hash(itemid, itemname);
                if (hashcode == 0) hashcode++;
            }
            return hashcode;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof CompactedItem) {
                return ((CompactedItem) obj).itemid.equals(this.itemid)
                    && ((CompactedItem) obj).itemname.equals(this.itemname);
            }
            return false;
        }
    }

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

    private static AE2Data sendRequest(REQUEST_OPERATION request) {
        requests.offer(request);
        int timeout = 0;
        while (!updates.containsKey(request) && timeout < 50) {
            try {
                Thread.sleep(200);
                timeout++;
            } catch (InterruptedException e) {
                return INVALID_DATA;
            }
        }
        if (timeout == 50) {
            return INVALID_DATA;
        }
        return updates.remove(request);
    }

    static class ListHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {

            if (preHTTPHandler(t)) return;
            AE2Data data = sendRequest(new LIST_CPUS());

            String response = GSONUtils.GSON_BUILDER.create()
                .toJson(data);
            byte[] raw_response = response.getBytes();
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }
    }

    static class GetHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {

            if (preHTTPHandler(t)) return;

            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                t.getRequestURI()
                    .getQuery());
            if (!GET_PARAMS.containsKey("cpu")) {
                return;
            }
            AE2Data data = sendRequest(new GET_CPU(GET_PARAMS.get("cpu")));
            String response;
            if (!data.isValid) response = GSONUtils.GSON_BUILDER.create()
                .toJson(data);
            else if (!data.clusters.containsKey(GET_PARAMS.get("cpu"))) response = GSONUtils.GSON_BUILDER.create()
                .toJson(data);
            else response = GSONUtils.GSON_BUILDER.create()
                .toJson(data.clusters.get(GET_PARAMS.get("cpu")));

            byte[] raw_response = response.getBytes();
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }
    }

    static class CancelCPUHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {

            if (preHTTPHandler(t)) return;

            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                t.getRequestURI()
                    .getQuery());
            if (!GET_PARAMS.containsKey("cpu")) {
                return;
            }

            AE2Data data = sendRequest(new CANCEL_CPU(GET_PARAMS.get("cpu")));
            String response = GSONUtils.GSON_BUILDER.create()
                .toJson(data);

            byte[] raw_response = response.getBytes();
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }

    }

    static class ItemsHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {

            if (preHTTPHandler(t)) return;

            AE2Data data = sendRequest(new GET_ITEMS());

            String response = GSONUtils.GSON_BUILDER.create()
                .toJson(data);
            byte[] raw_response = response.getBytes();
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }

    }

    static class OrderHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {

            if (preHTTPHandler(t)) return;

            Map<String, String> GET_PARAMS = HTTPUtils.parseQueryString(
                t.getRequestURI()
                    .getQuery());
            if (!GET_PARAMS.containsKey("item") || !GET_PARAMS.containsKey("quantity")) {
                return;
            }

            int hash = Integer.parseInt(GET_PARAMS.get("item"));
            int quantity = Integer.parseInt(GET_PARAMS.get("quantity"));
            IAEItemStack stack = hashcodeToAEItemStack.get(hash);

            if (stack == null || !stack.isCraftable()) {
                return;
            }

            AE2Data data = sendRequest(
                new BEGIN_ORDER(
                    stack.copy()
                        .setStackSize(quantity)));

            String response = GSONUtils.GSON_BUILDER.create()
                .toJson(data);
            byte[] raw_response = response.getBytes();
            t.sendResponseHeaders(200, raw_response.length);
            OutputStream os = t.getResponseBody();
            os.write(raw_response);
            os.close();
        }
    }

    static class JobHandler implements HttpHandler {

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

            REQUEST_OPERATION request;
            if (GET_PARAMS.containsKey("cancel")) request = new CANCEL_ORDER(id);
            else if (GET_PARAMS.containsKey("submit")) request = new SUBMIT_ORDER(id);
            else request = new CHECK_ORDER(id);

            AE2Data data = sendRequest(request);

            String response = GSONUtils.GSON_BUILDER.create()
                .toJson(data);
            byte[] raw_response = response.getBytes();
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
