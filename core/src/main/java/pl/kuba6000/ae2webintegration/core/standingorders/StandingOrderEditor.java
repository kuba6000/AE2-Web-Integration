package pl.kuba6000.ae2webintegration.core.standingorders;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * The only way HTTP threads change standing orders: stores the change and tells the engine about it, and limits how
 * often changes may make the engine read a whole network.
 * <p>
 * Every operation holds one monitor for its store access and its post, so the engine receives the changes in the
 * order the store committed them: a deletion can never be followed by the replacement it overtook, which would run an
 * order that no longer exists. One monitor serves all networks because the world database serializes writes anyway.
 * <p>
 * The engine reads the network once more when an order is run, when a definition refers to a resource it has no
 * stock for, and when an enabled order starts the network's stock feed. Such a change is accepted only if the
 * network's previous one was at least {@link #MIN_READ_INTERVAL_MILLIS} ago; renames, disabling, deleting and edits
 * that reuse the order's resources are never limited.
 */
public final class StandingOrderEditor {

    public static final long MIN_READ_INTERVAL_MILLIS = 5_000;

    /** What running an order now achieved. */
    public enum RunResult {
        /** The engine will check the order at its next tick. */
        QUEUED,
        /** The network has no such order. */
        NOT_FOUND,
        /**
         * The order is disabled, its stored definition cannot be read, or the engine reports its network switched off
         * or unavailable, so a check would do nothing.
         */
        NOT_RUNNABLE
    }

    /** The network's allowance for changes that cost a read is not available yet. */
    public static final class TooManyReadRequests extends IllegalStateException {

        @SuppressWarnings("MissingSerialAnnotation") // @Serial is unavailable on Java 8.
        private static final long serialVersionUID = 1L;

        private TooManyReadRequests() {
            super("Standing order read allowance not available yet");
        }
    }

    private final @NotNull StandingOrderStore store;
    private final @NotNull StandingOrderEngine engine;
    private final @NotNull LongSupplier nanoClock;
    private final Map<StableKey, Long> lastReadRequest = new HashMap<>();

    /** @param nanoClock monotonic time in nanoseconds */
    public StandingOrderEditor(@NotNull StandingOrderStore store, @NotNull StandingOrderEngine engine,
        @NotNull LongSupplier nanoClock) {
        this.store = store;
        this.engine = engine;
        this.nanoClock = nanoClock;
    }

    /**
     * Appends a new order to the network.
     *
     * @throws InvalidStandingOrderException        the definition violates the format or a limit
     * @throws StandingOrderStore.OrderLimitReached the network already holds the configured maximum
     * @throws StandingOrderStore.UnknownResource   a referenced resource is neither described nor in the catalogue
     * @throws TooManyReadRequests                  the network's read allowance is not available yet
     */
    public synchronized @NotNull StandingOrderStore.Valid create(@NotNull StableKey grid, @NotNull WebPrincipal creator,
        @NotNull StandingOrderDefinition definition, @NotNull Map<StableKey, ResourceDescriptor> descriptions)
        throws IOException, InvalidStandingOrderException {
        requireReadAllowance(grid);
        StandingOrderStore.Valid created = store.create(grid, creator, definition, descriptions);
        lastReadRequest.put(grid, nanoClock.getAsLong());
        engine.post(grid, created);
        return created;
    }

    /** Derives the new definition from the stored order, inside the editor's critical section. */
    @FunctionalInterface
    public interface Edit {

        @NotNull
        StandingOrderDefinition apply(@NotNull StandingOrderStore.Entry current) throws InvalidStandingOrderException;
    }

    /**
     * Replaces the definition of an order with the one the edit derives from its stored state, keeping its key,
     * position and creator. The derivation, the store access and the post are one step, so edits of the same order
     * cannot lose each other's changes.
     *
     * @param descriptions current descriptions of the resources of the derived definition
     * @return false when the network has no such order; true also when the edit changes nothing, which writes and posts
     *         nothing
     * @throws InvalidStandingOrderException      the edit refused, or the definition violates the format or a limit
     * @throws StandingOrderStore.UnknownResource a referenced resource is neither described nor in the catalogue
     * @throws TooManyReadRequests                the edit costs a read and the network's allowance is not available yet
     */
    public synchronized boolean update(@NotNull StableKey grid, @NotNull StableKey order, @NotNull Edit edit,
        @NotNull Function<StandingOrderDefinition, Map<StableKey, ResourceDescriptor>> descriptions)
        throws IOException, InvalidStandingOrderException {
        StandingOrderStore.Entry current = store.get(grid, order);
        if (current == null) return false;
        StandingOrderDefinition definition = edit.apply(current);
        if (current instanceof StandingOrderStore.Valid valid && valid.definition()
            .equals(definition)) return true;
        boolean costsRead = costsRead(current, definition);
        if (costsRead) requireReadAllowance(grid);
        if (!store.replace(grid, order, definition, descriptions.apply(definition))) return false;
        if (costsRead) lastReadRequest.put(grid, nanoClock.getAsLong());
        engine.post(grid, new StandingOrderStore.Valid(order, current.ordinal(), current.createdBy(), definition));
        return true;
    }

    /** @return false when the network has no such order */
    public synchronized boolean delete(@NotNull StableKey grid, @NotNull StableKey order) throws IOException {
        if (!store.delete(grid, order)) return false;
        engine.postDeletion(grid, order);
        return true;
    }

    /**
     * Asks the engine to check the order now.
     *
     * @throws TooManyReadRequests the order could run and the network's allowance is not available yet
     */
    public synchronized @NotNull RunResult run(@NotNull StableKey grid, @NotNull StableKey order) throws IOException {
        StandingOrderStore.Entry current = store.get(grid, order);
        if (current == null) return RunResult.NOT_FOUND;
        if (!(current instanceof StandingOrderStore.Valid valid) || !valid.definition()
            .enabled()) return RunResult.NOT_RUNNABLE;
        StandingOrderSnapshot status = engine.snapshot(order);
        if (status != null && (status.status() == StandingOrderStatus.SWITCHED_OFF
            || status.status() == StandingOrderStatus.GRID_UNAVAILABLE)) return RunResult.NOT_RUNNABLE;
        requireReadAllowance(grid);
        engine.requestRun(grid, order);
        lastReadRequest.put(grid, nanoClock.getAsLong());
        return RunResult.QUEUED;
    }

    /**
     * Whether the engine may read the network because of this edit: the order starts referring to a resource it did
     * not, or it is switched on. Nothing is known about the resources of an order that cannot be read.
     */
    private static boolean costsRead(@NotNull StandingOrderStore.Entry current,
        @NotNull StandingOrderDefinition definition) {
        Set<StableKey> known = new HashSet<>();
        boolean wasEnabled = false;
        if (current instanceof StandingOrderStore.Valid valid) {
            known.addAll(
                StandingOrderCodec.encode(valid.definition())
                    .resources());
            wasEnabled = valid.definition()
                .enabled();
        }
        return !known.containsAll(
            StandingOrderCodec.encode(definition)
                .resources())
            || definition.enabled() && !wasEnabled;
    }

    private void requireReadAllowance(@NotNull StableKey grid) {
        Long last = lastReadRequest.get(grid);
        if (last != null && nanoClock.getAsLong() - last < MILLISECONDS.toNanos(MIN_READ_INTERVAL_MILLIS)) {
            throw new TooManyReadRequests();
        }
    }
}
