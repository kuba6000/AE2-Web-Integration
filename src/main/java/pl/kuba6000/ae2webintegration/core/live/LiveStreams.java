package pl.kuba6000.ae2webintegration.core.live;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Bounded HTTP writers for live subscriptions. Topic state is independent of transport and persistence. */
public final class LiveStreams implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final Gson JSON = GSONUtils.GSON_BUILDER.create();
    private static final int MAX_EVENT_BYTES = 1024 * 1024;
    private static final long MAINTENANCE_MILLIS = 100;
    private static final long HEARTBEAT_MILLIS = 10_000;
    private static final long WRITE_TIMEOUT_MILLIS = 10_000;
    private final LiveHub hub;
    private final @Nullable IconPack icons;
    private final GridIdentityRegistry identities;
    private final List<Session> sessions = new ArrayList<>();
    private final ThreadPoolExecutor writers = new ThreadPoolExecutor(
        4,
        4,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64),
        task -> daemon(task, "AE2Web-LiveWriter"));
    private final ScheduledExecutorService timer = Executors
        .newSingleThreadScheduledExecutor(task -> daemon(task, "AE2Web-LiveTimer"));
    private volatile boolean closed;

    public LiveStreams(@NotNull LiveHub hub, @NotNull GridIdentityRegistry identities, @Nullable IconPack icons) {
        this.hub = hub;
        this.icons = icons;
        this.identities = identities;
        timer.scheduleWithFixedDelay(this::pump, MAINTENANCE_MILLIS, MAINTENANCE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Claims a single-use reservation created together with the initial snapshot. */
    public void open(@NotNull RequestContext context, @NotNull LiveTopic.Key key, @NotNull String token,
        boolean withIcons) throws IOException {
        context.getExchange()
            .getRequestBody()
            .close();
        LiveSubscription subscription = hub
            .attach(token, key, context.getSession(), withIcons, System.currentTimeMillis());
        if (subscription == null) {
            ApiResponse.error(ApiStatus.BAD_PARAM)
                .send(context.getExchange());
            return;
        }
        IconPack pack = withIcons ? icons : null;
        Session session = new Session(context, subscription, pack);
        if (withIcons && !Objects.equals(subscription.packId, pack == null ? null : pack.packId()))
            subscription.end = "reset";
        boolean accepted;
        synchronized (this) {
            accepted = !closed && context.isLifecycleUnchanged();
            if (accepted) sessions.add(session);
        }
        if (!accepted) {
            hub.release(subscription);
            ApiResponse.error(ApiStatus.SERVER_STOPPING)
                .send(context.getExchange());
            return;
        }
        schedule(session);
    }

    private void pump() {
        if (closed) return;
        long now = System.currentTimeMillis();
        hub.expire(now);
        List<Session> active;
        synchronized (this) {
            active = new ArrayList<>(sessions);
        }
        for (Session session : active) {
            try {
                authorize(session);
                if (session.hasTask()) {
                    if (session.writeTimedOut(
                        now,
                        session.subscription.end == null ? WRITE_TIMEOUT_MILLIS : MAINTENANCE_MILLIS)) finish(session);
                    continue;
                }
                if (session.subscription.end != null || !session.ready
                    || hub.hasPending(session.subscription)
                    || now - session.lastHeartbeat >= HEARTBEAT_MILLIS) schedule(session);
            } catch (RuntimeException failure) {
                LOG.warn("Live session maintenance failed", failure);
                finish(session);
            }
        }
    }

    private void schedule(Session session) {
        FutureTask<Void> task = session.prepareTask(() -> write(session));
        if (task == null) return;
        try {
            writers.execute(task);
        } catch (RejectedExecutionException unavailable) {
            finish(session);
        }
    }

    private void write(Session session) {
        if (!session.startWriting()) return;
        try {
            authorize(session);
            if (!session.ready) {
                session.context.getExchange()
                    .getResponseHeaders()
                    .set("Content-Type", "text/event-stream; charset=UTF-8");
                session.context.getExchange()
                    .getResponseHeaders()
                    .set("Cache-Control", "no-store");
                session.context.getExchange()
                    .getResponseHeaders()
                    .set("X-Accel-Buffering", "no");
                session.context.getExchange()
                    .sendResponseHeaders(200, 0);
                session.ready = true;
                sendControl(session, "ready");
            }
            LiveUpdate update = hub.drain(session.subscription);
            if (update != null) {
                IconMappings mappings = session.pack == null || !update.describes() ? null
                    : new IconMappings(session.pack);
                byte[] bytes = encode("delta", update.body(mappings));
                authorize(session);
                if (bytes.length > MAX_EVENT_BYTES) session.subscription.end = "reset";
                else if (session.subscription.end == null) output(session, bytes);
            }
            if (session.subscription.end != null) {
                sendControl(session, session.subscription.end);
                session.finished = true;
            } else if (System.currentTimeMillis() - session.lastHeartbeat >= HEARTBEAT_MILLIS) {
                // Delta traffic must not postpone heartbeat-driven lease renewal.
                sendControl(session, "heartbeat");
                session.lastHeartbeat = System.currentTimeMillis();
            }
        } catch (IOException failure) {
            LOG.debug("Live connection closed", failure);
            session.finished = true;
        } catch (RuntimeException failure) {
            LOG.warn("Live stream failed", failure);
            session.finished = true;
        } finally {
            if (Thread.currentThread()
                .isInterrupted()) session.finished = true;
            session.completeTask();
            try {
                if (session.finished) finish(session);
            } finally {
                // Socket cancellation interrupts the writer; the next client must not inherit the flag.
                Thread.interrupted();
            }
        }
    }

    private void authorize(Session session) {
        LiveSubscription subscription = session.subscription;
        if (subscription.end != null) return;
        if (!session.context.isLifecycleUnchanged()) subscription.end = "invalidate";
        else if (!subscription.session.isValid(System.currentTimeMillis())) subscription.end = "revoked";
        else {
            IAEGrid grid = identities.getGrid(
                subscription.topic.key()
                    .grid());
            if (grid == null) subscription.end = "invalidate";
            else if (!GridAccess.allows(grid, subscription.principal)) subscription.end = "revoked";
        }
    }

    private void finish(Session session) {
        session.cancelTask();
        try {
            session.context.getExchange()
                .close();
        } catch (RuntimeException failure) {
            LOG.warn("Live connection cleanup failed", failure);
        } finally {
            try {
                hub.release(session.subscription);
            } finally {
                synchronized (this) {
                    sessions.remove(session);
                }
            }
        }
    }

    private static byte[] encode(String event, Object body) {
        return ("event: " + event + "\ndata: " + JSON.toJson(body) + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void sendControl(Session session, String event) throws IOException {
        output(session, encode(event, new JsonObject()));
    }

    private static void output(Session session, byte[] bytes) throws IOException {
        session.context.getExchange()
            .getResponseBody()
            .write(bytes);
        session.context.getExchange()
            .getResponseBody()
            .flush();
    }

    /** HTTP restart retires connections and handoff tokens while keeping the world-owned transport usable. */
    public void reset() {
        List<Session> active;
        synchronized (this) {
            active = new ArrayList<>(sessions);
            hub.reset();
        }
        for (Session session : active) finish(session);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        reset();
        hub.close();
        timer.shutdownNow();
        writers.shutdownNow();
    }

    private static final class Session {

        private final RequestContext context;
        private final LiveSubscription subscription;
        private final @Nullable IconPack pack;
        private volatile boolean ready;
        private volatile boolean finished;
        private volatile long lastHeartbeat;
        private @Nullable FutureTask<Void> task;
        private long started;
        private boolean writing;

        private synchronized @Nullable FutureTask<Void> prepareTask(Runnable write) {
            if (task != null || finished) return null;
            task = new FutureTask<>(write, null);
            return task;
        }

        private synchronized boolean hasTask() {
            return task != null;
        }

        private synchronized boolean writeTimedOut(long now, long timeout) {
            return writing && now - started > timeout;
        }

        private synchronized boolean startWriting() {
            if (finished) return false;
            started = System.currentTimeMillis();
            writing = true;
            return true;
        }

        private synchronized void completeTask() {
            task = null;
            writing = false;
        }

        private void cancelTask() {
            FutureTask<Void> pending;
            synchronized (this) {
                finished = true;
                // Fence a writer that already started before this session failed authorization or timed out.
                if (subscription.end == null) subscription.end = "invalidate";
                pending = task;
            }
            if (pending != null) pending.cancel(true);
        }

        private Session(RequestContext context, LiveSubscription subscription, @Nullable IconPack pack) {
            this.context = context;
            this.subscription = subscription;
            this.pack = pack;
        }
    }
}
