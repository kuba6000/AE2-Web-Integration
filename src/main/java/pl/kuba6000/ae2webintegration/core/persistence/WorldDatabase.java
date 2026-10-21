package pl.kuba6000.ae2webintegration.core.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hsqldb.jdbc.JDBCDriver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.CraftingHistory;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.ResourceHistory;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;

/** One world owns its writer, bounded MVCC readers and file lifetime. */
public final class WorldDatabase implements AutoCloseable {

    private static final int GRID_FLUSH_INTERVAL_MINUTES = 5;
    private static final Set<Path> OPEN_DATABASES = ConcurrentHashMap.newKeySet();
    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final ReentrantLock writerGate = new ReentrantLock(true);
    private final Semaphore readers = new Semaphore(4, true);
    private final Path databaseBase;
    private volatile ResourceHistory policy;
    private volatile CraftingHistory craftingPolicy;
    private volatile @Nullable OpenedDatabase openedDatabase;
    private @Nullable Path ownedPath;
    private boolean closed;
    private volatile @Nullable GridIdentityRegistry identities;
    private @Nullable ScheduledFuture<?> gridFlush;
    private @Nullable ScheduledExecutorService gridFlushScheduler;
    private @Nullable Runnable historyMaintenance;
    private volatile boolean gridFlushStopped;

    @Desugar
    private record OpenedDatabase(@NotNull Connection writer, @NotNull String url) {}

    public WorldDatabase(@NotNull Path databaseBase, @NotNull ResourceHistory policy) {
        this(databaseBase, policy, new CraftingHistory());
    }

    public WorldDatabase(@NotNull Path databaseBase, @NotNull ResourceHistory policy,
        @NotNull CraftingHistory craftingPolicy) {
        if (databaseBase.toString()
            .indexOf(';') >= 0)
            throw new IllegalArgumentException("Database path cannot contain JDBC property separators");
        this.databaseBase = databaseBase.toAbsolutePath();
        this.policy = policy;
        this.craftingPolicy = craftingPolicy;
    }

    public @NotNull ResourceHistory policy() {
        return policy;
    }

    public @NotNull CraftingHistory craftingPolicy() {
        return craftingPolicy;
    }

    /** Publishes validated, read-only settings to this active world without database work. */
    public boolean applyPolicy(@NotNull ResourceHistory policy) {
        return applyPolicy(policy, craftingPolicy);
    }

    public boolean applyPolicy(@NotNull ResourceHistory policy, @NotNull CraftingHistory craftingPolicy) {
        GridIdentityRegistry current = identities;
        if (current == null || gridFlushStopped) return false;
        synchronized (current) {
            if (identities != current || gridFlushStopped || !current.applyPolicy(this, policy, craftingPolicy))
                return false;
            this.policy = policy;
            this.craftingPolicy = craftingPolicy;
        }
        synchronized (this) {
            if (!gridFlushStopped && gridFlushScheduler != null) gridFlushScheduler.execute(this::maintain);
        }
        return true;
    }

    public void open() throws IOException {
        write(db -> null);
    }

    /** Operations borrow the connection only for this transaction and must not call the registry. */
    @FunctionalInterface
    public interface Transaction<T> {

        T run(@NotNull Connection db) throws SQLException, IOException;
    }

    public <T> T write(@NotNull Transaction<T> transaction) throws IOException {
        flushGridIdentities();
        return writeIdentities(transaction);
    }

    /** Bind only after this world's identities have loaded successfully. */
    public void bindGridIdentities(@NotNull GridIdentityRegistry registry) {
        identities = registry;
    }

    /** The world lifecycle owns the executor; this owner fences and cancels its periodic task. */
    public synchronized void startGridFlush(@NotNull ScheduledExecutorService scheduler,
        @NotNull Runnable historyMaintenance) {
        if (gridFlush != null || gridFlushStopped) return;
        this.historyMaintenance = historyMaintenance;
        gridFlushScheduler = scheduler;
        gridFlush = scheduler.scheduleWithFixedDelay(
            this::maintain,
            GRID_FLUSH_INTERVAL_MINUTES,
            GRID_FLUSH_INTERVAL_MINUTES,
            TimeUnit.MINUTES);
    }

    private void maintain() {
        if (gridFlushStopped) return;
        try {
            flushGridIdentities();
            if (!gridFlushStopped && historyMaintenance != null) historyMaintenance.run();
        } catch (IOException | RuntimeException failure) {
            LOG.error("World data maintenance failed; pending changes will be retried", failure);
        }
    }

    public synchronized void stopGridFlush() {
        gridFlushStopped = true;
        if (gridFlush != null) gridFlush.cancel(false);
    }

    /** Acquire the registry before any database locks, so workers never reverse the metadata lock order. */
    public void flushGridIdentities() throws IOException {
        GridIdentityRegistry current = identities;
        if (current == null) return;
        synchronized (current) {
            // A final flush may have detached this binding while this worker waited for the registry.
            if (identities == current) current.saveIfDirty(this);
        }
    }

    /** A successful final flush lets draining history use old durable rows after RAM admission closes. */
    public void finishGridIdentities() throws IOException {
        GridIdentityRegistry current = identities;
        if (current == null) return;
        synchronized (current) {
            current.saveIfDirty(this);
            if (identities == current) identities = null;
        }
    }

    /** Identity load/save is the only mutation allowed to bypass the dependency flush. */
    <T> T writeIdentities(@NotNull Transaction<T> transaction) throws IOException {
        lifecycle.readLock()
            .lock();
        writerGate.lock();
        try {
            Connection db = database().writer();
            try {
                T value = transaction.run(db);
                db.commit();
                return value;
            } catch (SQLException | IOException | RuntimeException | Error failure) {
                try {
                    db.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("World database write failed", failure);
        } finally {
            writerGate.unlock();
            lifecycle.readLock()
                .unlock();
        }
    }

    public <T> T read(@NotNull Transaction<T> transaction) throws IOException {
        try {
            readers.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("Interrupted while waiting to read world data", interrupted);
        }
        lifecycle.readLock()
            .lock();
        try {
            if (closed) throw new IOException("World database is closed");
            OpenedDatabase initialized = openedDatabase;
            if (initialized == null) {
                writerGate.lock();
                try {
                    initialized = database();
                } finally {
                    writerGate.unlock();
                }
            }
            try (Connection db = connect(initialized.url())) {
                db.setReadOnly(true);
                db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                db.setAutoCommit(false);
                T result = transaction.run(db);
                db.commit();
                return result;
            }
        } catch (SQLException failure) {
            throw new IOException("World database read failed", failure);
        } finally {
            lifecycle.readLock()
                .unlock();
            readers.release();
        }
    }

    private @NotNull OpenedDatabase database() throws IOException, SQLException {
        if (closed) throw new IOException("World database is closed");
        OpenedDatabase current = openedDatabase;
        if (current != null) return current;
        Files.createDirectories(databaseBase.getParent());
        Path canonical = databaseBase.getParent()
            .toRealPath()
            .resolve(databaseBase.getFileName());
        if (!OPEN_DATABASES.add(canonical)) throw new IOException("World database is still owned by an active service");
        ownedPath = canonical;
        String address = "jdbc:hsqldb:file:" + canonical.toString()
            .replace('\\', '/');
        Connection opened;
        try {
            opened = connect(address);
        } catch (SQLException failure) {
            releaseOwnership();
            throw failure;
        }
        try {
            WorldSchema.initialize(opened);
            opened.setAutoCommit(false);
            WorldGridStore.normalizePolicy(opened, policy);
            WorldGridStore.normalizeCraftingPolicy(opened, craftingPolicy);
            opened.commit();
            OpenedDatabase initialized = new OpenedDatabase(opened, address);
            openedDatabase = initialized;
            return initialized;
        } catch (SQLException | IOException | RuntimeException | Error failure) {
            try {
                shutdown(opened);
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static @NotNull Connection connect(@NotNull String url) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", "SA");
        properties.setProperty("password", "");
        return new JDBCDriver().connect(url, properties);
    }

    @Override
    public void close() throws IOException {
        stopGridFlush();
        lifecycle.writeLock()
            .lock();
        try {
            if (closed) return;
            closed = true;
            OpenedDatabase current = openedDatabase;
            if (current == null) return;
            try {
                shutdown(current.writer());
            } catch (SQLException failure) {
                throw new IOException("Cannot close world database", failure);
            } finally {
                openedDatabase = null;
            }
        } finally {
            lifecycle.writeLock()
                .unlock();
        }
    }

    private void shutdown(@NotNull Connection db) throws SQLException {
        SQLException failure = null;
        boolean stopped = false;
        try (Statement sql = db.createStatement()) {
            sql.execute("SHUTDOWN");
            stopped = true;
        } catch (SQLException failed) {
            failure = failed;
        }
        try {
            db.close();
        } catch (SQLException failed) {
            if (failure == null) failure = failed;
            else failure.addSuppressed(failed);
        }
        if (stopped) releaseOwnership();
        if (failure != null) throw failure;
    }

    private void releaseOwnership() {
        if (ownedPath != null) {
            OPEN_DATABASES.remove(ownedPath);
            ownedPath = null;
        }
    }
}
