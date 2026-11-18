package pl.kuba6000.ae2webintegration.core.standingorders;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.WebPrincipal;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * The only way HTTP threads change standing orders: stores the change and tells the engine about it.
 * <p>
 * Every operation holds one monitor for its store access and its post, so the engine receives the changes in the
 * order the store committed them: a deletion can never be followed by the replacement it overtook, which would run an
 * order that no longer exists. One monitor serves all networks because the world database serializes writes anyway.
 * <p>
 * No operation is refused because of what it costs the network. The engine reads the network again when an order is
 * run, when a definition refers to a resource it has no stock for, and when an enabled order starts the network's
 * stock feed; the tracking coordinator coalesces and spaces those reads.
 */
public final class StandingOrderEditor {

    /** What running an order now achieved. */
    public enum RunResult {
        /** The engine will check the order at its next tick. */
        QUEUED,
        /** The network has no such order. */
        NOT_FOUND,
        /**
         * The order is disabled, its stored definition cannot be read, or the network's switch is off.
         */
        NOT_RUNNABLE
    }

    /** What changing an order achieved. */
    public enum UpdateResult {
        /** The order has the derived definition now; also when the edit changed nothing. */
        UPDATED,
        /** The network has no such order. */
        NOT_FOUND,
        /** The edit would switch on an order that is off, which is not allowed while standing orders are disabled. */
        ENABLING_REFUSED
    }

    private final @NotNull StandingOrderStore store;
    private final @NotNull StandingOrderEngine engine;

    public StandingOrderEditor(@NotNull StandingOrderStore store, @NotNull StandingOrderEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    /**
     * Appends a new order to the network.
     *
     * @throws InvalidStandingOrderException        the definition violates the format or a limit
     * @throws StandingOrderStore.OrderLimitReached the network already holds the configured maximum
     * @throws StandingOrderStore.UnknownResource   a referenced resource is not known to the account on this grid
     */
    public synchronized @NotNull StandingOrderStore.Valid create(@NotNull StableKey grid, @NotNull WebPrincipal creator,
        @NotNull StandingOrderDefinition definition, @NotNull Map<StableKey, ResourceDescriptor> descriptions)
        throws IOException, InvalidStandingOrderException {
        StandingOrderStore.Valid created = store.create(grid, creator, definition, descriptions);
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
     * @param mayEnable    whether the edit may switch on an order that is off or cannot be read
     * @return what happened; an edit that changes nothing writes and posts nothing
     * @throws InvalidStandingOrderException      the edit refused, or the definition violates the format or a limit
     * @throws StandingOrderStore.UnknownResource a referenced resource is not known to the account on this grid
     */
    public synchronized @NotNull UpdateResult update(@NotNull WebPrincipal principal, @NotNull StableKey grid,
        @NotNull StableKey order, @NotNull Edit edit,
        @NotNull Function<StandingOrderDefinition, Map<StableKey, ResourceDescriptor>> descriptions, boolean mayEnable)
        throws IOException, InvalidStandingOrderException {
        StandingOrderStore.Entry current = store.get(grid, order);
        if (current == null) return UpdateResult.NOT_FOUND;
        StandingOrderDefinition definition = edit.apply(current);
        boolean wasEnabled = false;
        if (current instanceof StandingOrderStore.Valid valid) {
            if (valid.definition()
                .equals(definition)) return UpdateResult.UPDATED;
            wasEnabled = valid.definition()
                .enabled();
        }
        if (definition.enabled() && !wasEnabled && !mayEnable) return UpdateResult.ENABLING_REFUSED;
        if (!store.replace(principal, grid, order, definition, descriptions.apply(definition)))
            return UpdateResult.NOT_FOUND;
        engine.post(grid, new StandingOrderStore.Valid(order, current.ordinal(), current.createdBy(), definition));
        return UpdateResult.UPDATED;
    }

    /** @return false when the network has no such order */
    public synchronized boolean delete(@NotNull StableKey grid, @NotNull StableKey order) throws IOException {
        if (!store.delete(grid, order)) return false;
        engine.postDeletion(grid, order);
        return true;
    }

    /** Asks the engine to check the order now. */
    public synchronized @NotNull RunResult run(@NotNull StableKey grid, @NotNull StableKey order, boolean gridEnabled)
        throws IOException {
        StandingOrderStore.Entry current = store.get(grid, order);
        if (current == null) return RunResult.NOT_FOUND;
        if (!gridEnabled || !(current instanceof StandingOrderStore.Valid valid)
            || !valid.definition()
                .enabled())
            return RunResult.NOT_RUNNABLE;
        engine.requestRun(grid, order);
        return RunResult.QUEUED;
    }
}
