package pl.kuba6000.ae2webintegration.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.auth.AuthService;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.http.ApiRouter;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.WebHandler;
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

public class AE2Controller {

    public static IAE AE2Interface;
    public static IServerPlatform serverPlatform;

    private static HttpServer server;
    private static ExecutorService serverThread;
    private static final Object serverLifecycleLock = new Object();
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

    // Package-private: the tick pump in CoreEngine is the only consumer, and after X-01 nothing outside
    // core touches the queue at all.
    private static final int SERVER_THREAD_QUEUE_CAPACITY = 32;
    static final BlockingQueue<IServerThreadTask> requests = new ArrayBlockingQueue<>(SERVER_THREAD_QUEUE_CAPACITY);

    private static final long AUTH_LOOKUP_TIMEOUT_SECONDS = 2L;
    private static final long REQUEST_TIMEOUT_SECONDS = 10L;

    public static final class ServerTaskUnavailableException extends Exception {

        @SuppressWarnings("MissingSerialAnnotation") // @Serial is unavailable on Java 8.
        private static final long serialVersionUID = 1L;

        private final ApiStatus status;

        private ServerTaskUnavailableException(ApiStatus status) {
            super(status.name());
            this.status = status;
        }

        public @NotNull ApiStatus getStatus() {
            return status;
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

    public static void startHTTPServer() {
        synchronized (serverLifecycleLock) {
            if (server != null || serverThread != null) {
                throw new IllegalStateException("HTTP server is already running");
            }

            AuthService.reloadHttpSettings();
            ExecutorService newServerThread = createHTTPExecutor();
            HttpServer newServer = null;
            try {
                newServer = HttpServer.create(new InetSocketAddress(Config.AE_PORT()), HTTP_BACKLOG);
                ApiRouter api = new ApiRouter(
                    AuthService::authenticate,
                    AuthService::isRateLimited,
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
            new ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY));
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
        AuthService.clearWorldState();
        itemIdentities.clear();
    }

    public static final ItemIdentityRegistry itemIdentities = new ItemIdentityRegistry();

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
    public static @Nullable UUID findOnlinePlayerOnServerThread(@NotNull String username)
        throws ServerTaskUnavailableException {
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

    /** Whether a request still belongs to the currently running HTTP listener. */
    public static boolean isCurrentHTTPLifecycle(long generation) {
        return acceptingHTTPRequests && httpLifecycleGeneration.get() == generation;
    }

    public static void init() {
        try {
            startHTTPServer();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

}
