package pl.kuba6000.ae2webintegration.core.http.stream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StoragePublicationListener;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageSnapshot;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Bounded shared terminal replay and asynchronous HTTP writers; native access is confined to onServerTick. */
public final class TerminalStreams implements StoragePublicationListener, AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final Gson JSON = GSONUtils.GSON_BUILDER.create();
    private static final int ENCODED_REPLAY_BYTES = 16 * 1024 * 1024;
    private static final int WRITER_THREADS = 4;
    private static final long PUMP_INTERVAL_MILLIS = 100;
    private static final int MAX_CURSOR_LENGTH = 41;
    private static final int MAX_STREAMS = 64;
    private static final int MAX_PER_PRINCIPAL = 4;
    private static final int MAX_GRIDS = 64;
    private static final int REPLAY_EVENTS = 64;
    private static final int REPLAY_ROWS = 16_384;
    private static final int MAX_EVENT_BYTES = 1024 * 1024;
    private static final long SNAPSHOT_WAIT_MILLIS = 2000;
    private static final long HEARTBEAT_MILLIS = 10_000;
    private static final long WRITE_TIMEOUT_MILLIS = 10_000;
    private static final long IDLE_RETENTION_MILLIS = 30_000;
    private static final long ACCESS_LEASE_MILLIS = 2000;
    private static final long ACCESS_CHECK_MILLIS = 1000;
    private final Supplier<IconPack> icons;
    private final Predicate<RequestContext> authenticated;
    private final Map<StableKey, State> states = new HashMap<>();
    private final List<Session> sessions = new ArrayList<>();
    private final Semaphore snapshotWaiters = new Semaphore(16);
    private final Semaphore encodedCapacity = new Semaphore(ENCODED_REPLAY_BYTES);
    private final ThreadPoolExecutor writers = new ThreadPoolExecutor(
        WRITER_THREADS,
        WRITER_THREADS,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(MAX_STREAMS),
        task -> daemon(task, "AE2Web-TerminalWriter"));
    private final ScheduledExecutorService timer = Executors
        .newSingleThreadScheduledExecutor(task -> daemon(task, "AE2Web-TerminalTimer"));
    private volatile boolean closed;
    private long nextAccessCheck;

    @Desugar
    public record View(@NotNull String generation, @NotNull String revision) {

        public @NotNull String cursor() {
            return generation + ":" + revision;
        }
    }

    @Desugar
    public record Delta(@NotNull View view, @NotNull List<StoredResource> upserts, @NotNull List<StableKey> removed,
        @NotNull List<StoredResource> unkeyed, @Nullable IconMappings icons) {}

    public TerminalStreams(@NotNull Supplier<IconPack> icons, @NotNull Predicate<RequestContext> authenticated) {
        this.icons = icons;
        this.authenticated = authenticated;
        timer.scheduleWithFixedDelay(this::pump, PUMP_INTERVAL_MILLIS, PUMP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public static @NotNull View view(@NotNull StorageSnapshot snapshot) {
        return new View(Long.toString(snapshot.generation()), Long.toString(snapshot.revision()));
    }

    /** Validates the wire cursor without interpreting it as a JavaScript number. */
    public static boolean validCursor(@Nullable String cursor) {
        if (cursor == null || cursor.length() > MAX_CURSOR_LENGTH) return false;
        String[] parts = cursor.split(":", -1);
        if (parts.length != 2) return false;
        try {
            long generation = Long.parseLong(parts[0]);
            long revision = Long.parseLong(parts[1]);
            return generation > 0 && revision > 0 && (generation + ":" + revision).equals(cursor);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /** HTTP-thread wait only. Capacity and time are bounded independently of open stream admission. */
    public @Nullable StorageSnapshot awaitSnapshot(@NotNull StableKey key, long generation,
        @NotNull Supplier<StorageSnapshot> current) {
        if (!snapshotWaiters.tryAcquire()) return null;
        try {
            StorageSnapshot initial = current.get();
            if (initial == null || initial.generation() != generation) return null;
            State state = state(key, generation, initial.lossEpoch());
            if (state == null) return null;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SNAPSHOT_WAIT_MILLIS);
            while (!closed && !state.retired) {
                StorageSnapshot candidate = current.get();
                if (candidate == null || candidate.generation() != generation) return null;
                advanceEpoch(state, candidate.lossEpoch());
                if (candidate.revision() > 0) publish(state, candidate);
                synchronized (state) {
                    if (state.retired) return null;
                    StorageSnapshot snapshot = state.snapshot;
                    if (snapshot != null && snapshot.generation() != generation) return null;
                    if (snapshot != null && snapshot.revision() > 0 && snapshot.stockComplete()) {
                        state.touched = System.currentTimeMillis();
                        state.advertised = true;
                        return snapshot;
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return null;
                    TimeUnit.NANOSECONDS.timedWait(state, remaining);
                }
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            return null;
        } finally {
            snapshotWaiters.release();
        }
    }

    private synchronized @Nullable State state(StableKey key, long generation, long lossEpoch) {
        if (closed) return null;
        State existing = states.get(key);
        if (existing != null) {
            if (existing.generation == generation) {
                advanceEpoch(existing, lossEpoch);
                return existing;
            }
            if (existing.generation > generation) return null;
            retireState(existing);
            states.remove(key);
        }
        expireStates(System.currentTimeMillis());
        if (states.size() >= MAX_GRIDS) return null;
        State created = new State(generation, lossEpoch);
        states.put(key, created);
        return created;
    }

    @Override
    public void published(@NotNull StableKey key, @NotNull StorageSnapshot snapshot) {
        State state;
        synchronized (this) {
            state = states.get(key);
        }
        if (state != null) publish(state, snapshot);
    }

    private void publish(State state, StorageSnapshot snapshot) {
        if (closed || state.retired || snapshot.generation() != state.generation || snapshot.revision() == 0) return;
        advanceEpoch(state, snapshot.lossEpoch());
        while (!closed && !state.retired && snapshot.lossEpoch() == state.lossEpoch) {
            StorageSnapshot previous = state.snapshot;
            if (previous != null && previous.generation() == snapshot.generation()
                && previous.revision() >= snapshot.revision()) return;
            Frame frame = previous == null || previous.generation() != snapshot.generation() ? null
                : difference(previous, snapshot);
            synchronized (state) {
                if (state.snapshot != previous) continue;
                if (state.retired || snapshot.lossEpoch() != state.lossEpoch) return;
                if (frame == null) {
                    for (Frame old : state.frames) releaseEncoding(old);
                    state.frames.clear();
                    state.rows = 0;
                } else {
                    state.frames.addLast(frame);
                    state.rows += frame.cost();
                    while (state.frames.size() > REPLAY_EVENTS || state.rows > REPLAY_ROWS) {
                        Frame oldest = state.frames.removeFirst();
                        state.rows -= oldest.cost();
                        releaseEncoding(oldest);
                    }
                }
                state.snapshot = snapshot;
                state.notifyAll();
                return;
            }
        }
    }

    @Override
    public void retired(@NotNull StableKey key, long generation) {
        State state;
        synchronized (this) {
            state = states.get(key);
            if (state == null || state.generation != generation) return;
            states.remove(key);
            for (Session session : sessions) if (session.key.equals(key)) session.end = "invalidate";
        }
        retireState(state);
    }

    @Override
    public void retired(@NotNull StableKey key, long generation, long lossEpoch) {
        State state;
        synchronized (this) {
            state = states.get(key);
        }
        if (state != null && state.generation == generation) advanceEpoch(state, lossEpoch);
    }

    private synchronized void advanceEpoch(State state, long lossEpoch) {
        if (state.retired || lossEpoch <= state.lossEpoch) return;
        synchronized (state) {
            state.lossEpoch = lossEpoch;
            state.snapshot = null;
            state.advertised = false;
            for (Frame frame : state.frames) releaseEncoding(frame);
            state.frames.clear();
            state.rows = 0;
            state.notifyAll();
        }
        for (Session session : sessions) {
            if (session.state == state) session.end = "invalidate";
        }
    }

    private void retireState(State state) {
        state.retired = true;
        synchronized (state) {
            for (Frame frame : state.frames) releaseEncoding(frame);
            state.frames.clear();
            state.notifyAll();
        }
    }

    /** Called only after the route's game-thread grid permission check succeeds. */
    public void open(@NotNull RequestContext context, @NotNull StableKey key, @NotNull String cursor, boolean withIcons)
        throws IOException {
        context.getExchange()
            .getRequestBody()
            .close();
        if (!validCursor(cursor)) {
            ApiResponse.error(ApiStatus.BAD_PARAM)
                .send(context.getExchange());
            return;
        }
        Session session = null;
        synchronized (this) {
            long principalStreams = sessions.stream()
                .filter(
                    s -> s.context.getPrincipal()
                        .equals(context.getPrincipal()))
                .count();
            if (!closed && sessions.size() < MAX_STREAMS && principalStreams < MAX_PER_PRINCIPAL) {
                State state = states.get(key);
                session = new Session(context, key, state, cursor, withIcons, withIcons ? icons.get() : null);
                if (state == null || !state.advertised) session.end = "reset";
                sessions.add(session);
            }
        }
        if (session == null) ApiResponse.error(ApiStatus.SERVER_BUSY)
            .send(context.getExchange());
        else schedule(session);
    }

    /** Game-thread authorization and one viewer renewal per grid, regardless of its number of clients. */
    public void onServerTick(@NotNull IAE ae, long nowMillis, @NotNull BiConsumer<IAEGrid, Boolean> renewViewer) {
        if (nowMillis < nextAccessCheck) return;
        nextAccessCheck = nowMillis + ACCESS_CHECK_MILLIS;
        List<Session> active;
        synchronized (this) {
            if (sessions.isEmpty()) return;
            active = new ArrayList<>(sessions);
        }
        Map<StableKey, GridAccess.View> grids = new HashMap<>();
        try {
            for (GridAccess.View grid : GridAccess.list(ae)) grids.put(grid.key(), grid);
        } catch (IOException failure) {
            for (Session session : active) session.end = "invalidate";
            return;
        }
        Map<IAEGrid, Boolean> renew = new HashMap<>();
        for (Session session : active) {
            if (session.end != null) continue;
            GridAccess.View grid = grids.get(session.key);
            if (grid == null) {
                session.end = "invalidate";
                continue;
            }
            if (!authenticated.test(session.context) || !grid.allows(session.context.getPrincipal())) {
                session.end = "revoked";
                continue;
            }
            session.authorizedUntil = nowMillis + ACCESS_LEASE_MILLIS;
            renew.merge(grid.grid(), session.withIcons, Boolean::logicalOr);
        }
        renew.forEach(renewViewer);
    }

    private void pump() {
        if (closed) return;
        long now = System.currentTimeMillis();
        List<Session> active;
        synchronized (this) {
            active = new ArrayList<>(sessions);
            expireStates(now);
        }
        for (Session session : active) {
            synchronized (session) {
                if (session.task != null) {
                    if (session.writing && now - session.started > WRITE_TIMEOUT_MILLIS) {
                        session.end = "reset";
                        session.task.cancel(true);
                    }
                    continue;
                }
            }
            if (!authenticated.test(session.context)) session.end = "revoked";
            if (now > session.authorizedUntil) session.end = "invalidate";
            StorageSnapshot snapshot = session.state == null ? null : session.state.snapshot;
            if (session.end != null || !session.ready
                || now - session.lastWrite >= HEARTBEAT_MILLIS
                || snapshot != null && !view(snapshot).cursor()
                    .equals(session.cursor))
                schedule(session);
        }
    }

    private synchronized void expireStates(long now) {
        Iterator<Map.Entry<StableKey, State>> iterator = states.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<StableKey, State> entry = iterator.next();
            boolean active = false;
            for (Session session : sessions) if (session.key.equals(entry.getKey())) {
                active = true;
                break;
            }
            if (!active && now - entry.getValue().touched > IDLE_RETENTION_MILLIS) {
                retireState(entry.getValue());
                iterator.remove();
            }
        }
    }

    private void schedule(Session session) {
        synchronized (session) {
            if (session.task != null || session.finished) return;
            session.started = System.currentTimeMillis();
            FutureTask<Void> task = new FutureTask<>(() -> {
                write(session);
                return null;
            });
            session.task = task;
            try {
                writers.execute(task);
            } catch (RejectedExecutionException unavailable) {
                session.task = null;
                session.end = "reset";
            }
        }
    }

    private void write(Session session) {
        synchronized (session) {
            session.started = System.currentTimeMillis();
            session.writing = true;
        }
        try {
            if (!authenticated.test(session.context)) session.end = "revoked";
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
                send(session, "ready", session.cursor, new JsonObject());
            }
            List<Frame> frames = pending(session);
            for (Frame frame : frames) {
                if (session.end != null || !authenticated.test(session.context)) break;
                if (!frame.stockComplete) {
                    session.end = "gap";
                    break;
                }
                byte[] encoded = encoded(frame, session.withIcons ? session.pack : null);
                if (encoded == null) {
                    session.end = "reset";
                    break;
                }
                if (!authenticated.test(session.context)) {
                    session.end = "revoked";
                    break;
                }
                if (System.currentTimeMillis() > session.authorizedUntil) {
                    session.end = "invalidate";
                    break;
                }
                session.context.getExchange()
                    .getResponseBody()
                    .write(encoded);
                session.context.getExchange()
                    .getResponseBody()
                    .flush();
                session.cursor = frame.view.cursor();
            }
            if (session.end != null) {
                send(session, session.end, null, new JsonObject());
                session.finished = true;
            } else {
                session.context.getExchange()
                    .getResponseBody()
                    .write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                session.context.getExchange()
                    .getResponseBody()
                    .flush();
            }
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
            if (session.finished) {
                session.context.getExchange()
                    .close();
                synchronized (this) {
                    sessions.remove(session);
                    if (session.state != null) session.state.touched = System.currentTimeMillis();
                }
            }
            synchronized (session) {
                session.task = null;
                session.writing = false;
            }
            // SocketChannel cancellation sets the writer's interrupt flag; the next client must not inherit it.
            Thread.interrupted();
        }
    }

    private List<Frame> pending(Session session) {
        State state = session.state;
        if (session.end != null || state == null) return Collections.emptyList();
        IconPack pack = icons.get();
        if (session.withIcons && !samePack(pack, session.pack)) {
            session.end = "reset";
            return Collections.emptyList();
        }
        synchronized (state) {
            StorageSnapshot latest = state.snapshot;
            if (state.retired || latest == null) {
                session.end = "invalidate";
                return Collections.emptyList();
            }
            if (view(latest).cursor()
                .equals(session.cursor)) return Collections.emptyList();
            List<Frame> result = new ArrayList<>();
            String cursor = session.cursor;
            for (Frame frame : state.frames) {
                if (frame.previous.equals(cursor)) {
                    result.add(frame);
                    cursor = frame.view.cursor();
                }
            }
            if (!cursor.equals(view(latest).cursor())) {
                session.end = "reset";
                return Collections.emptyList();
            }
            return result;
        }
    }

    private static boolean samePack(IconPack left, IconPack right) {
        return left == right || left != null && right != null
            && left.packId()
                .equals(right.packId());
    }

    private static boolean send(Session session, String event, @Nullable String cursor, Object body)
        throws IOException {
        String json = JSON.toJson(body);
        byte[] bytes = ((cursor == null ? "" : "id: " + cursor + "\n") + "event: " + event + "\ndata: " + json + "\n\n")
            .getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EVENT_BYTES) return false;
        OutputStream output = session.context.getExchange()
            .getResponseBody();
        output.write(bytes);
        output.flush();
        return true;
    }

    private byte @Nullable [] encoded(Frame frame, @Nullable IconPack pack) {
        AtomicReference<Encoded> cache = pack == null ? frame.plain : frame.icons;
        String packId = pack == null ? "" : pack.packId();
        synchronized (frame) {
            Encoded existing = cache.get();
            if (existing != null && existing.packId.equals(packId)) return existing.bytes;
            String json = JSON.toJson(encode(frame, pack));
            byte[] bytes = ("id: " + frame.view.cursor() + "\nevent: delta\ndata: " + json + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_EVENT_BYTES) return null;
            if (!frame.discarded && encodedCapacity.tryAcquire(bytes.length)) {
                Encoded replacement = new Encoded(packId, bytes);
                Encoded old = cache.getAndSet(replacement);
                if (old != null) encodedCapacity.release(old.bytes.length);
                // Retiring a grid cannot wait for JSON encoding on the game thread.
                if (frame.discarded && cache.compareAndSet(replacement, null)) encodedCapacity.release(bytes.length);
            }
            return bytes;
        }
    }

    private void releaseEncoding(Frame frame) {
        frame.discarded = true;
        Encoded plain = frame.plain.getAndSet(null);
        Encoded icons = frame.icons.getAndSet(null);
        if (plain != null) encodedCapacity.release(plain.bytes.length);
        if (icons != null) encodedCapacity.release(icons.bytes.length);
    }

    private static Frame difference(StorageSnapshot previous, StorageSnapshot next) {
        Map<StableKey, StorageObservation.Row> old = keyed(previous);
        List<StorageObservation.Row> upserts = new ArrayList<>();
        List<StorageObservation.Row> unkeyed = new ArrayList<>();
        for (StorageObservation.Row row : next.rows()) {
            StableKey key = row.resource()
                .itemKey();
            if (key == null) unkeyed.add(row);
            else if (!row.equals(old.remove(key))) upserts.add(row);
        }
        return new Frame(view(previous).cursor(), next, upserts, new ArrayList<>(old.keySet()), unkeyed);
    }

    private static Map<StableKey, StorageObservation.Row> keyed(StorageSnapshot snapshot) {
        Map<StableKey, StorageObservation.Row> result = new HashMap<>();
        for (StorageObservation.Row row : snapshot.rows()) {
            StableKey key = row.resource()
                .itemKey();
            if (key != null) result.put(key, row);
        }
        return result;
    }

    private static Delta encode(Frame frame, @Nullable IconPack pack) {
        IconMappings mappings = pack == null ? null : new IconMappings(pack);
        return new Delta(
            frame.view,
            resources(frame.upserts, mappings),
            frame.removed,
            resources(frame.unkeyed, mappings),
            mappings);
    }

    public static @NotNull List<StoredResource> resources(@NotNull List<StorageObservation.Row> rows,
        @Nullable IconMappings mappings) {
        List<StoredResource> result = new ArrayList<>(rows.size());
        for (StorageObservation.Row row : rows) {
            ResourceSnapshot resource = row.resource();
            result.add(
                new StoredResource(
                    resource.registryNamespace(),
                    resource.registryPath(),
                    resource.displayName(),
                    resource.componentCount(),
                    resource.damage(),
                    resource.resourceType(),
                    resource.quantity(),
                    row.craftable(),
                    resource.itemKey(),
                    mappings == null || resource.itemKey() == null ? null
                        : mappings.resolve(resource.itemKey(), resource.iconBaseKey())));
        }
        return result;
    }

    @Override
    public void close() {
        List<Session> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = new ArrayList<>(sessions);
            for (State state : states.values()) retireState(state);
            states.clear();
        }
        for (Session session : active) {
            session.end = "invalidate";
            synchronized (session) {
                if (session.writing) session.task.cancel(true);
                else schedule(session);
            }
        }
        writers.shutdown();
        try {
            if (!writers.awaitTermination(2, TimeUnit.SECONDS)) writers.shutdownNow();
        } catch (InterruptedException interrupted) {
            writers.shutdownNow();
            Thread.currentThread()
                .interrupt();
        }
        timer.shutdownNow();
    }

    private static final class State {

        private final long generation;
        private volatile long lossEpoch;
        private volatile boolean advertised;
        private volatile @Nullable StorageSnapshot snapshot;
        private volatile boolean retired;
        private volatile long touched = System.currentTimeMillis();
        private final Deque<Frame> frames = new ArrayDeque<>();
        private int rows;

        private State(long generation, long lossEpoch) {
            this.generation = generation;
            this.lossEpoch = lossEpoch;
        }
    }

    private static final class Encoded {

        private final String packId;
        private final byte[] bytes;

        private Encoded(String packId, byte[] bytes) {
            this.packId = packId;
            this.bytes = bytes;
        }
    }

    private static final class Frame {

        private final AtomicReference<Encoded> plain = new AtomicReference<>();
        private final AtomicReference<Encoded> icons = new AtomicReference<>();
        private volatile boolean discarded;

        private final String previous;
        private final View view;
        private final boolean stockComplete;
        private final List<StorageObservation.Row> upserts;
        private final List<StableKey> removed;
        private final List<StorageObservation.Row> unkeyed;

        private Frame(String previous, StorageSnapshot snapshot, List<StorageObservation.Row> upserts,
            List<StableKey> removed, List<StorageObservation.Row> unkeyed) {
            this.previous = previous;
            this.view = view(snapshot);
            this.stockComplete = snapshot.stockComplete();
            this.upserts = upserts;
            this.removed = removed;
            this.unkeyed = unkeyed;
        }

        private int cost() {
            return upserts.size() + removed.size() + unkeyed.size();
        }
    }

    private static final class Session {

        private final RequestContext context;
        private final StableKey key;
        private final @Nullable State state;
        private final boolean withIcons;
        private final @Nullable IconPack pack;
        private volatile String cursor;
        private volatile @Nullable String end;
        private volatile boolean ready;
        private volatile boolean finished;
        private volatile long authorizedUntil = System.currentTimeMillis() + ACCESS_LEASE_MILLIS;
        private volatile long lastWrite;
        private @Nullable FutureTask<Void> task;
        private long started;
        private boolean writing;

        private Session(RequestContext context, StableKey key, @Nullable State state, String cursor, boolean withIcons,
            @Nullable IconPack pack) {
            this.context = context;
            this.key = key;
            this.state = state;
            this.cursor = cursor;
            this.withIcons = withIcons;
            this.pack = pack;
        }
    }
}
