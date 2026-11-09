package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IIdentityHolder;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Producer-owned stable keys and primitive counters; native identities belong to the shared registry. */
public final class StorageCapture implements IIdentityHolder, AutoCloseable {

    private final Thread producer = Thread.currentThread();
    private int maxResources;
    private final boolean flowEnabled;
    private final WeakReference<IAEGrid> grid;
    private final Map<StableKey, Entry> resources = new HashMap<>();
    private final List<Entry> pending = new ArrayList<>();
    private final List<Entry> flowPending = new ArrayList<>();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean reconcile = new AtomicBoolean(true);
    private long sequence;
    private int absoluteDepth;
    private boolean absoluteFailed;
    private boolean hasUnkeyedResources;
    private boolean stockNeeded = true;
    private boolean closed;
    private final List<Entry> staged = new ArrayList<>();

    public StorageCapture(@NotNull IAEGrid grid, int maxResources) {
        this(grid, maxResources, true);
    }

    public StorageCapture(@NotNull IAEGrid grid, int maxResources, boolean flowEnabled) {
        this.grid = new WeakReference<>(grid);
        this.flowEnabled = flowEnabled;
        if (maxResources < 1) throw new IllegalArgumentException("Resource limit must be positive");
        this.maxResources = maxResources;
    }

    /** Changes admission only: already captured identities and undrained flow remain owned. */
    public void setMaximumResources(int maximumResources) {
        if (!isProducerThread()) throw new IllegalStateException("Resource limits require the producer thread");
        if (maximumResources < 1) throw new IllegalArgumentException("Resource limit must be positive");
        maxResources = maximumResources;
    }

    /** Idle flow capture needs only undrained counters; returning viewers require a fresh stock baseline. */
    public void setStockNeeded(boolean needed) {
        if (!isProducerThread()) throw new IllegalStateException("Stock demand requires the producer thread");
        if (closed || stockNeeded == needed) return;
        stockNeeded = needed;
        invalidate();
        if (needed) return;
        hasUnkeyedResources = false;
        for (Iterator<Entry> iterator = resources.values()
            .iterator(); iterator.hasNext();) {
            Entry entry = iterator.next();
            entry.present = false;
            entry.dirty = 0;
            entry.hasAbsolute = false;
            entry.pending = false;
            if (unused(entry)) {
                iterator.remove();
                releaseIfUnused(entry);
            }
        }
        pending.clear();
    }

    /** Owner cleanup runs after native detachment, or after the world producer has stopped. */
    @Override
    public void close() {
        closed = true;
        resources.clear();
        pending.clear();
        flowPending.clear();
        staged.clear();
        AE2Controller.itemIdentities.beginListing(this)
            .commit();
    }

    public boolean isProducerThread() {
        return Thread.currentThread() == producer;
    }

    /** Native event callbacks must not propagate capture failures into gameplay. */
    private @Nullable Entry tryAdmit(IAEKey resource) {
        if (!isProducerThread()) {
            markGap();
            return null;
        }
        try {
            return admit(resource);
        } catch (RuntimeException failure) {
            hasUnkeyedResources = true;
            markGap();
            return null;
        }
    }

    private @Nullable Entry admit(IAEKey resource) {
        if (closed) return null;
        if (!resource.web$supportsStableIdentity()) {
            hasUnkeyedResources = true;
            return null;
        }
        // At saturation, avoid retaining new identities in this capture's shared registry ownership.
        if (resources.size() >= maxResources) {
            StableKey key = resource.web$getKey();
            if (key == null) {
                hasUnkeyedResources = true;
                return null;
            }
            if (!resources.containsKey(key)) {
                markGap();
                throw new IllegalStateException(
                    "Storage resource capacity reached: retained=" + resources.size() + ", limit=" + maxResources);
            }
        }
        IAEGrid owner = grid.get();
        if (owner == null) throw new IllegalStateException("Storage grid is no longer available");
        StableKey key = AE2Controller.itemIdentities.remember(this, resource);
        if (key == null) {
            hasUnkeyedResources = true;
            return null;
        }
        return resources.computeIfAbsent(key, Entry::new);
    }

    /** Called only for a completed real operation; rejected and simulated operations add nothing. */
    public void recordFlow(@NotNull IAEKey resource, long input, long output) {
        if (input < 0 || output < 0) throw new IllegalArgumentException("Flow must be nonnegative");
        if (input == 0 && output == 0) return;
        Entry entry = tryAdmit(resource);
        if (entry == null) return;
        if (flowEnabled) {
            if (!entry.flowPending) {
                entry.flowPending = true;
                flowPending.add(entry);
            }
            entry.input = add(entry, entry.input, input);
            entry.output = add(entry, entry.output, output);
            entry.inputOperations = add(entry, entry.inputOperations, input > 0 ? 1 : 0);
            entry.outputOperations = add(entry, entry.outputOperations, output > 0 ? 1 : 0);
        }
        if (stockNeeded) {
            dirty(entry);
            entry.hasAbsolute = false;
        }
    }

    public void markChanged(@NotNull IAEKey resource) {
        if (!stockNeeded || closed) return;
        Entry entry = tryAdmit(resource);
        if (entry == null) return;
        dirty(entry);
        entry.hasAbsolute = false;
    }

    private final AtomicLong terminalEpoch = new AtomicLong();

    /** Native full-list/topology reset, distinct from a refresh of changed craftability. */
    public void invalidateTerminal() {
        terminalEpoch.incrementAndGet();
        invalidate();
    }

    public long terminalEpoch() {
        return terminalEpoch.get();
    }

    public void invalidate() {
        reconcile.set(true);
    }

    /** A rejected history write loses flow coverage, not the current native stock state. */
    public void markFlowGap() {
        lost.set(true);
    }

    /** May be called by an unexpected producer or a failed native observation. */
    public void markGap() {
        lost.set(true);
        reconcile.set(true);
    }

    public boolean requiresReconciliation() {
        return reconcile.get();
    }

    public void beginAbsoluteChanges() {
        if (closed) return;
        if (!isProducerThread()) {
            markGap();
            return;
        }
        if (absoluteDepth++ == 0) absoluteFailed = false;
    }

    public void absoluteChanged(@NotNull IAEKey resource, long amount) {
        if (!stockNeeded || closed) return;
        Entry entry = tryAdmit(resource);
        if (entry == null) return;
        if (absoluteDepth == 0 || amount < 0) {
            markGap();
            return;
        }
        if (!entry.staged) {
            staged.add(entry);
            entry.staged = true;
        }
        entry.stagedAmount = amount;
    }

    public void endAbsoluteChanges(boolean success) {
        if (closed) return;
        if (!isProducerThread()) {
            markGap();
            return;
        }
        if (absoluteDepth == 0) throw new IllegalStateException("No stock observation is open");
        absoluteFailed |= !success;
        if (--absoluteDepth != 0) return;
        for (Entry entry : staged) {
            entry.staged = false;
            if (!absoluteFailed && stockNeeded) {
                entry.absolute = entry.stagedAmount;
                entry.hasAbsolute = true;
                dirty(entry);
            }
            releaseIfUnused(entry);
        }
        staged.clear();
        if (absoluteFailed) invalidate();
    }

    /** Native enumeration and metadata remain on the producer; only the returned value may be queued. */
    public @NotNull StorageObservation observe(@NotNull IAEGrid grid, long observedAt, boolean full) {
        return observe(grid, observedAt, full, true);
    }

    /** Stock reads may update the live view without draining the independently timed flow bucket. */
    public @NotNull StorageObservation observe(@NotNull IAEGrid grid, long observedAt, boolean full,
        boolean flushFlow) {
        if (!isProducerThread()) throw new IllegalStateException("Native observation requires the producer thread");
        if (closed) throw new IllegalStateException("Storage capture is closed");
        if (absoluteDepth != 0) throw new IllegalStateException("Native stock refresh is incomplete");
        setStockNeeded(true);
        boolean requested = reconcile.getAndSet(false);
        boolean complete = full || requested;
        List<StorageObservation.Row> rows = new ArrayList<>();
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        List<StorageObservation.UnkeyedRow> unkeyed = new ArrayList<>();
        boolean scanUnkeyed = hasUnkeyedResources;
        hasUnkeyedResources = false;
        try {
            IStackList list = complete ? grid.web$getStorageGrid()
                .web$getStorageSnapshot()
                : grid.web$getStorageGrid()
                    .web$getStorageList();
            long through = sequence;
            if (complete) {
                Set<IAEKey> included = new HashSet<>();
                Set<IAEKey> craftables = grid.web$getCraftingGrid()
                    .web$getCraftables(null);
                if (!resources.isEmpty()) {
                    // Reclaim proven absences before admission, so replacing a full catalogue fits the same cap.
                    for (IAEGenericStack stack : list.web$stacks()) included.add(stack.web$what());
                    included.addAll(craftables);
                    releaseAbsent(included, through);
                    included.clear();
                }
                for (IAEGenericStack stack : list.web$stacks()) {
                    IAEKey key = stack.web$what();
                    row(grid, key, stack.web$amount(), rows, descriptions, unkeyed);
                    included.add(key);
                }
                for (IAEKey craftable : craftables) {
                    if (included.add(craftable)) row(grid, craftable, 0, rows, descriptions, unkeyed);
                }
            } else {
                Set<IAEKey> included = new HashSet<>();
                for (int index = 0; index < pending.size();) {
                    Entry entry = pending.get(index);
                    if (entry.dirty != 0 && entry.dirty <= through) {
                        IAEKey identity = identity(entry);
                        if (identity == null) {
                            discard(entry);
                            continue;
                        }
                        long amount = entry.hasAbsolute ? entry.absolute : list.web$getAmount(identity);
                        row(grid, identity, amount, rows, descriptions, unkeyed);
                        included.add(identity);
                    }
                    index++;
                }
                if (scanUnkeyed || hasUnkeyedResources) {
                    for (IAEGenericStack stack : list.web$stacks()) {
                        IAEKey key = stack.web$what();
                        if (needsUnkeyedRead(key) && included.add(key)) {
                            row(grid, key, stack.web$amount(), rows, descriptions, unkeyed);
                        }
                    }
                    for (IAEKey craftable : grid.web$getCraftingGrid()
                        .web$getCraftables(null)) {
                        if (needsUnkeyedRead(craftable) && included.add(craftable))
                            row(grid, craftable, 0, rows, descriptions, unkeyed);
                    }
                }
            }
            List<StorageObservation.Flow> flows = flushFlow ? drainFlow(descriptions) : Collections.emptyList();
            StorageObservation result = new StorageObservation(
                observedAt,
                complete,
                flushFlow ? !lost.getAndSet(false) : !lost.get(),
                rows,
                flows,
                descriptions,
                unkeyed);
            for (Entry entry : pending) {
                if (entry.dirty <= through) {
                    entry.dirty = 0;
                    entry.hasAbsolute = false;
                }
            }
            prunePending();
            return result;
        } catch (RuntimeException failure) {
            hasUnkeyedResources |= scanUnkeyed;
            invalidate();
            throw failure;
        }
    }

    /** Detaches accepted flow without touching a grid, inventory, craftables or any stock cache. */
    public @NotNull StorageObservation flushFlow(long observedAt) {
        if (!isProducerThread()) throw new IllegalStateException("Flow capture requires the producer thread");
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        List<StorageObservation.Flow> flows = drainFlow(descriptions);
        prunePending();
        return new StorageObservation(
            observedAt,
            false,
            !lost.getAndSet(false),
            false,
            Collections.emptyList(),
            flows,
            descriptions,
            Collections.emptyList());
    }

    private List<StorageObservation.Flow> drainFlow(Map<StableKey, ResourceDescriptor> descriptions) {
        // Metadata callbacks may themselves trigger native events. Complete them before draining counters.
        for (int index = 0; index < flowPending.size();) {
            Entry entry = flowPending.get(index);
            IAEKey identity = identity(entry);
            if (identity == null) {
                discard(entry);
                continue;
            }
            if (entry.input != 0 || entry.output != 0) AE2Controller.itemIdentities.describe(entry.key);
            index++;
        }
        List<StorageObservation.Flow> flows = new ArrayList<>();
        for (int index = 0; index < flowPending.size();) {
            Entry entry = flowPending.get(index);
            // A later metadata callback may have discovered a collision for an earlier entry.
            IAEKey identity = identity(entry);
            if (identity == null) {
                discard(entry);
                continue;
            }
            if (entry.input != 0 || entry.output != 0) {
                if (entry.overflow) markGap();
                descriptions.put(entry.key, AE2Controller.itemIdentities.describe(entry.key));
                flows.add(
                    new StorageObservation.Flow(
                        entry.key,
                        entry.input,
                        entry.output,
                        entry.inputOperations,
                        entry.outputOperations));
            }
            entry.input = 0;
            entry.output = 0;
            entry.inputOperations = 0;
            entry.outputOperations = 0;
            entry.overflow = false;
            entry.flowPending = false;
            releaseIfUnused(entry);
            index++;
        }
        flowPending.clear();
        return flows;
    }

    private void prunePending() {
        for (int index = pending.size() - 1; index >= 0; index--) {
            Entry entry = pending.get(index);
            if (entry.dirty != 0 || entry.input != 0 || entry.output != 0) continue;
            entry.pending = false;
            int last = pending.size() - 1;
            pending.set(index, pending.get(last));
            pending.remove(last);
            releaseIfUnused(entry);
        }
    }

    private void row(IAEGrid grid, IAEKey key, long amount, List<StorageObservation.Row> rows,
        Map<StableKey, ResourceDescriptor> descriptions, List<StorageObservation.UnkeyedRow> unkeyed) {
        Entry entry;
        try {
            entry = admit(key);
        } catch (IllegalArgumentException | UnsupportedOperationException invalidIdentity) {
            markGap();
            unkeyed.add(unkeyedRow(grid, key, amount));
            return;
        }
        if (entry == null) {
            unkeyed.add(unkeyedRow(grid, key, amount));
            return;
        }
        IAEKey identity = identity(entry);
        if (identity == null) {
            discard(entry);
            unkeyed.add(unkeyedRow(grid, key, amount));
            return;
        }
        descriptions.put(entry.key, AE2Controller.itemIdentities.describe(entry.key));
        boolean craftable = grid.web$getCraftingGrid()
            .web$isCurrentlyCraftable(key);
        entry.present = amount != 0 || craftable;
        rows.add(new StorageObservation.Row(entry.key, amount, craftable));
        releaseIfUnused(entry);
    }

    private @Nullable IAEKey identity(Entry entry) {
        try {
            return AE2Controller.itemIdentities.resolve(entry.key);
        } catch (ItemIdentityRegistry.Ambiguous unavailable) {
            return null;
        }
    }

    private static boolean unused(Entry entry) {
        return !entry.present && entry.dirty == 0 && !entry.flowPending && !entry.staged;
    }

    private void releaseAbsent(Set<IAEKey> included, long through) {
        for (Iterator<Entry> iterator = resources.values()
            .iterator(); iterator.hasNext();) {
            Entry entry = iterator.next();
            IAEKey identity = identity(entry);
            if (identity == null) {
                iterator.remove();
                discard(entry);
                continue;
            }
            if (included.contains(identity)) continue;
            entry.present = false;
            if (entry.dirty <= through) entry.dirty = 0;
            if (unused(entry)) {
                iterator.remove();
                releaseIfUnused(entry);
            }
        }
        prunePending();
    }

    private void releaseIfUnused(Entry entry) {
        if (!unused(entry)) return;
        resources.remove(entry.key);
        AE2Controller.itemIdentities.release(this, entry.key);
    }

    private void discard(Entry entry) {
        resources.remove(entry.key);
        AE2Controller.itemIdentities.release(this, entry.key);
        pending.remove(entry);
        flowPending.remove(entry);
        staged.remove(entry);
        hasUnkeyedResources = true;
        markGap();
    }

    private boolean needsUnkeyedRead(IAEKey key) {
        return tryAdmit(key) == null;
    }

    private StorageObservation.UnkeyedRow unkeyedRow(IAEGrid grid, IAEKey key, long amount) {
        hasUnkeyedResources = true;
        return new StorageObservation.UnkeyedRow(
            ResourceSnapshot.capture(key, amount, null),
            key.web$isCraftable(grid));
    }

    private long add(Entry entry, long current, long amount) {
        if (Long.MAX_VALUE - current < amount) {
            entry.overflow = true;
            return Long.MAX_VALUE;
        }
        return current + amount;
    }

    private void dirty(Entry entry) {
        entry.dirty = ++sequence;
        if (!entry.pending) {
            entry.pending = true;
            pending.add(entry);
        }
    }

    private static final class Entry {

        private final StableKey key;
        private long input;
        private long output;
        private long inputOperations;
        private long outputOperations;
        private boolean overflow;
        private boolean flowPending;
        private boolean present;
        private long dirty;
        private boolean pending;
        private boolean hasAbsolute;
        private long absolute;
        private boolean staged;
        private long stagedAmount;

        private Entry(StableKey key) {
            this.key = key;
        }
    }
}
