package pl.kuba6000.ae2webintegration.core.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

import com.github.bsideup.jabel.Desugar;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.dto.StoredResource;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Bounded HTTP writers for live subscriptions. Storage state is independent of transport and persistence. */
public final class TerminalStreams implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final Gson JSON = GSONUtils.GSON_BUILDER.create();
    private static final int MAX_EVENT_BYTES = 1024 * 1024;
    private static final long MAINTENANCE_MILLIS = 100;
    private static final long HEARTBEAT_MILLIS = 10_000;
    private static final long WRITE_TIMEOUT_MILLIS = 10_000;
    private final TerminalRegistry state;
    private final @Nullable IconPack icons;
    private final GridIdentityRegistry identities;
    private final List<Session> sessions = new ArrayList<>();
    private final ThreadPoolExecutor writers = new ThreadPoolExecutor(
        4,
        4,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64),
        task -> daemon(task, "AE2Web-TerminalWriter"));
    private final ScheduledExecutorService timer = Executors
        .newSingleThreadScheduledExecutor(task -> daemon(task, "AE2Web-TerminalTimer"));
    private volatile boolean closed;

    @Desugar
    public record Delta(@NotNull TerminalRegistry.View view, @NotNull List<TerminalRegistry.Amount> updates,
        @NotNull List<StoredResource> definitions, @NotNull List<StableKey> removed,
        @Nullable List<StoredResource> unkeyed, @Nullable IconMappings icons) {}

    public TerminalStreams(@NotNull TerminalRegistry state, @NotNull GridIdentityRegistry identities,
        @Nullable IconPack icons) {
        this.state = state;
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
    public void open(@NotNull RequestContext context, @NotNull StableKey key, @NotNull String token, boolean withIcons)
        throws IOException {
        context.getExchange()
            .getRequestBody()
            .close();
        TerminalRegistry.Subscription subscription = state
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
            state.release(subscription);
            ApiResponse.error(ApiStatus.SERVER_STOPPING)
                .send(context.getExchange());
            return;
        }
        schedule(session);
    }

    private void pump() {
        if (closed) return;
        long now = System.currentTimeMillis();
        state.expire(now);
        List<Session> active;
        synchronized (this) {
            active = new ArrayList<>(sessions);
        }
        for (Session session : active) {
            authorize(session);
            if (session.hasTask()) {
                if (session
                    .writeTimedOut(now, session.subscriber.end == null ? WRITE_TIMEOUT_MILLIS : MAINTENANCE_MILLIS))
                    finish(session);
                continue;
            }
            if (session.subscriber.end != null || !session.ready
                || state.hasPending(session.subscriber)
                || now - session.lastWrite >= HEARTBEAT_MILLIS) schedule(session);
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
                send(session, "ready", new JsonObject());
            }
            TerminalRegistry.Update update = state.drain(session.subscriber);
            if (update != null) {
                boolean describe = !update.definitions()
                    .isEmpty() || update.unkeyed() != null
                        && !update.unkeyed()
                            .isEmpty();
                IconMappings mappings = session.pack == null || !describe ? null : new IconMappings(session.pack);
                Delta delta = new Delta(
                    update.view(),
                    update.updates(),
                    resources(update.definitions(), update.descriptions(), Collections.emptyList(), mappings),
                    update.removed(),
                    update.unkeyed() == null ? null
                        : resources(Collections.emptyList(), Collections.emptyMap(), update.unkeyed(), mappings),
                    mappings);
                byte[] bytes = encode("delta", delta);
                authorize(session);
                if (bytes.length > MAX_EVENT_BYTES) session.subscriber.end = "reset";
                else if (session.subscriber.end == null) output(session, bytes);
            }
            if (session.subscriber.end != null) {
                send(session, session.subscriber.end, new JsonObject());
                session.finished = true;
            } else output(session, ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
            session.lastWrite = System.currentTimeMillis();
        } catch (IOException failure) {
            LOG.debug("Terminal connection closed", failure);
            session.finished = true;
        } catch (RuntimeException failure) {
            LOG.warn("Terminal stream failed", failure);
            session.finished = true;
        } finally {
            if (Thread.currentThread()
                .isInterrupted()) session.finished = true;
            session.completeTask();
            if (session.finished) finish(session);
            // Socket cancellation interrupts the writer; the next client must not inherit the flag.
            Thread.interrupted();
        }
    }

    private void authorize(Session session) {
        if (session.subscriber.end != null) return;
        if (!session.context.isLifecycleUnchanged()) session.subscriber.end = "invalidate";
        else if (!session.subscriber.session.isValid(System.currentTimeMillis())) session.subscriber.end = "revoked";
        else {
            IAEGrid grid = identities.getGrid(session.subscriber.key);
            if (grid == null) session.subscriber.end = "invalidate";
            else if (!GridAccess.allows(grid, session.subscriber.principal)) session.subscriber.end = "revoked";
        }
    }

    private void finish(Session session) {
        session.cancelTask();
        session.context.getExchange()
            .close();
        state.release(session.subscriber);
        synchronized (this) {
            sessions.remove(session);
        }
    }

    private static byte[] encode(String event, Object body) {
        return ("event: " + event + "\ndata: " + JSON.toJson(body) + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void send(Session session, String event, Object body) throws IOException {
        output(session, encode(event, body));
    }

    private static void output(Session session, byte[] bytes) throws IOException {
        session.context.getExchange()
            .getResponseBody()
            .write(bytes);
        session.context.getExchange()
            .getResponseBody()
            .flush();
    }

    public static @NotNull List<StoredResource> resources(@NotNull List<StorageObservation.Row> rows,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @NotNull List<StorageObservation.UnkeyedRow> unkeyed,
        @Nullable IconMappings mappings) {
        List<StoredResource> result = new ArrayList<>(rows.size() + unkeyed.size());
        for (StorageObservation.Row row : rows) {
            result.add(resource(descriptions.get(row.itemKey()), row.quantity(), row.craftable(), mappings));
        }
        for (StorageObservation.UnkeyedRow row : unkeyed) {
            result.add(
                resource(
                    row.resource()
                        .description(),
                    row.resource()
                        .quantity(),
                    row.craftable(),
                    mappings));
        }
        return result;
    }

    private static StoredResource resource(ResourceDescriptor resource, long quantity, boolean craftable,
        @Nullable IconMappings mappings) {
        return new StoredResource(
            resource.registryNamespace(),
            resource.registryPath(),
            resource.displayName(),
            resource.componentCount(),
            resource.damage(),
            resource.resourceType(),
            quantity,
            craftable,
            resource.itemKey(),
            mappings == null || resource.itemKey() == null ? null
                : mappings.resolve(resource.itemKey(), resource.baseKey()));
    }

    /** HTTP restart retires connections and handoff tokens while keeping the world-owned transport usable. */
    public void reset() {
        List<Session> active;
        synchronized (this) {
            active = new ArrayList<>(sessions);
            state.reset();
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
        state.close();
        timer.shutdownNow();
        writers.shutdownNow();
    }

    private static final class Session {

        private final RequestContext context;
        private final TerminalRegistry.Subscription subscriber;
        private final @Nullable IconPack pack;
        private volatile boolean ready;
        private volatile boolean finished;
        private volatile long lastWrite;
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
                pending = task;
            }
            if (pending != null) pending.cancel(true);
        }

        private Session(RequestContext context, TerminalRegistry.Subscription subscriber, @Nullable IconPack pack) {
            this.context = context;
            this.subscriber = subscriber;
            this.pack = pack;
        }
    }
}
