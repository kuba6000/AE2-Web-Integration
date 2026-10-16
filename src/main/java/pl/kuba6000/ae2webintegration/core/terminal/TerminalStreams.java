package pl.kuba6000.ae2webintegration.core.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Bounded HTTP writers for live subscriptions. Storage state is independent of transport and persistence. */
public final class TerminalStreams implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final Gson JSON = GSONUtils.GSON_BUILDER.create();
    private static final int MAX_EVENT_BYTES = 1024 * 1024;
    private static final long HEARTBEAT_MILLIS = 10_000;
    private static final long WRITE_TIMEOUT_MILLIS = 10_000;
    private static final long ACCESS_LEASE_MILLIS = 2000;
    private final TerminalState state;
    private final Supplier<IconPack> icons;
    private final Predicate<RequestContext> authenticated;
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
    private long nextAccessCheck;

    @Desugar
    public record Delta(@NotNull TerminalState.View view, @NotNull List<TerminalState.Amount> updates,
        @NotNull List<StoredResource> definitions, @NotNull List<StableKey> removed,
        @Nullable List<StoredResource> unkeyed, @Nullable IconMappings icons) {}

    public TerminalStreams(@NotNull TerminalState state, @NotNull Supplier<IconPack> icons,
        @NotNull Predicate<RequestContext> authenticated) {
        this.state = state;
        this.icons = icons;
        this.authenticated = authenticated;
        timer.scheduleWithFixedDelay(this::pump, 100, 100, TimeUnit.MILLISECONDS);
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
        TerminalState.Subscription subscription = state
            .attach(token, key, context.getPrincipal(), withIcons, System.currentTimeMillis());
        if (subscription == null) {
            ApiResponse.error(ApiStatus.BAD_PARAM)
                .send(context.getExchange());
            return;
        }
        IconPack pack = withIcons ? icons.get() : null;
        Session session = new Session(context, subscription, pack);
        if (withIcons && !Objects.equals(subscription.packId, pack == null ? null : pack.packId()))
            subscription.end = "reset";
        synchronized (this) {
            if (closed) {
                state.release(subscription);
                ApiResponse.error(ApiStatus.SERVER_STOPPING)
                    .send(context.getExchange());
                return;
            }
            sessions.add(session);
        }
        schedule(session);
    }

    /** Includes unopened reservations so the snapshot-to-stream interval retains native observation. */
    public void onServerTick(@NotNull IAE ae, long now, @NotNull Consumer<IAEGrid> renewViewer) {
        if (now < nextAccessCheck) return;
        nextAccessCheck = now + 1000;
        state.expire(now);
        List<TerminalState.Subscription> subscribers = state.subscribers();
        if (subscribers.isEmpty()) return;
        Map<StableKey, GridAccess.View> grids = new HashMap<>();
        try {
            for (GridAccess.View grid : GridAccess.list(ae)) grids.put(grid.key(), grid);
        } catch (IOException failure) {
            for (TerminalState.Subscription subscriber : subscribers) subscriber.end = "invalidate";
            return;
        }
        Set<IAEGrid> renew = new HashSet<>();
        for (TerminalState.Subscription subscriber : subscribers) {
            if (subscriber.end != null) continue;
            GridAccess.View grid = grids.get(subscriber.key);
            if (grid == null) subscriber.end = "invalidate";
            else if (!grid.allows(subscriber.principal)) subscriber.end = "revoked";
            else renew.add(grid.grid());
        }
        synchronized (this) {
            for (Session session : sessions) {
                if (session.subscriber.end == null) session.authorizedUntil = now + ACCESS_LEASE_MILLIS;
            }
        }
        renew.forEach(renewViewer);
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
            synchronized (session) {
                FutureTask<Void> task = session.task;
                if (task != null) {
                    if (session.writing && now - session.started > WRITE_TIMEOUT_MILLIS) {
                        session.subscriber.end = "reset";
                        task.cancel(true);
                    }
                    continue;
                }
            }
            if (!authenticated.test(session.context)) session.subscriber.end = "revoked";
            if (now > session.authorizedUntil) session.subscriber.end = "invalidate";
            if (session.subscriber.end != null || !session.ready
                || state.hasPending(session.subscriber)
                || now - session.lastWrite >= HEARTBEAT_MILLIS) schedule(session);
        }
    }

    private void schedule(Session session) {
        synchronized (session) {
            if (session.task != null || session.finished) return;
            FutureTask<Void> task = new FutureTask<>(() -> {
                write(session);
                return null;
            });
            session.task = task;
            try {
                writers.execute(task);
            } catch (RejectedExecutionException unavailable) {
                session.task = null;
                finish(session);
            }
        }
    }

    private void write(Session session) {
        synchronized (session) {
            session.started = System.currentTimeMillis();
            session.writing = true;
        }
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
            if (session.subscriber.icons && !samePack(icons.get(), session.pack)) session.subscriber.end = "reset";
            TerminalState.Update update = state.drain(session.subscriber);
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
            if (session.finished) finish(session);
            synchronized (session) {
                session.task = null;
                session.writing = false;
            }
            // Socket cancellation interrupts the writer; the next client must not inherit the flag.
            Thread.interrupted();
        }
    }

    private void authorize(Session session) {
        if (!authenticated.test(session.context)) session.subscriber.end = "revoked";
        else if (System.currentTimeMillis() > session.authorizedUntil) session.subscriber.end = "invalidate";
    }

    private void finish(Session session) {
        session.finished = true;
        session.context.getExchange()
            .close();
        state.release(session.subscriber);
        synchronized (this) {
            sessions.remove(session);
        }
    }

    private static boolean samePack(IconPack left, IconPack right) {
        return left == right || left != null && right != null
            && left.packId()
                .equals(right.packId());
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

    @Override
    public void close() {
        List<Session> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = new ArrayList<>(sessions);
        }
        state.close();
        for (Session session : active) synchronized (session) {
            FutureTask<Void> task = session.task;
            if (task != null) task.cancel(true);
            finish(session);
        }
        timer.shutdownNow();
        writers.shutdownNow();
    }

    private static final class Session {

        private final RequestContext context;
        private final TerminalState.Subscription subscriber;
        private final @Nullable IconPack pack;
        private volatile boolean ready;
        private volatile boolean finished;
        private volatile long authorizedUntil = System.currentTimeMillis() + ACCESS_LEASE_MILLIS;
        private volatile long lastWrite;
        private @Nullable FutureTask<Void> task;
        private long started;
        private boolean writing;

        private Session(RequestContext context, TerminalState.Subscription subscriber, @Nullable IconPack pack) {
            this.context = context;
            this.subscriber = subscriber;
            this.pack = pack;
        }
    }
}
