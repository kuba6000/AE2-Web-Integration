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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Producer-owned stable keys and primitive counters; native identities belong to the shared registry. */
public final class StorageCapture {

    private final Thread producer = Thread.currentThread();
    private final int maxResources;
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
        } catch (UnsupportedOperationException unsupported) {
            hasUnkeyedResources = true;
            return null;
        } catch (RuntimeException failure) {
            hasUnkeyedResources = true;
            markGap();
            return null;
        }
    }

    private @Nullable Entry admit(IAEKey resource) {
        if (!resource.web$supportsStableIdentity()) {
            hasUnkeyedResources = true;
            return null;
        }
        // At saturation, avoid retaining new identities in the grid's shared registry ownership.
        if (resources.size() >= maxResources && !resources.containsKey(resource.web$getKey())) {
            markGap();
            throw new IllegalStateException(
                "Storage resource capacity reached: retained=" + resources.size() + ", limit=" + maxResources);
        }
        IAEGrid owner = grid.get();
        if (owner == null) throw new IllegalStateException("Storage grid is no longer available");
        StableKey key = AE2Controller.itemIdentities.remember(owner, resource);
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
        }
        dirty(entry);
        entry.hasAbsolute = false;
    }

    public void markChanged(@NotNull IAEKey resource) {
        Entry entry = tryAdmit(resource);
        if (entry == null) return;
        dirty(entry);
        entry.hasAbsolute = false;
    }

    /** Keep removed resources available while this capture still needs their identity for stock or flow. */
    public void retainIdentities(@NotNull ItemIdentityRegistry.Listing listing) {
        Iterator<Entry> iterator = resources.values()
            .iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (identity(entry) == null) {
                iterator.remove();
                discard(entry);
            } else {
                listing.retain(entry.key);
            }
        }
    }

    public void invalidate() {
        reconcile.set(true);
    }

    /** May be called by an unexpected producer or after rejected worker publication. */
    public void markGap() {
        lost.set(true);
        reconcile.set(true);
    }

    public boolean requiresReconciliation() {
        return reconcile.get();
    }

    public void beginAbsoluteChanges() {
        if (!isProducerThread()) {
            markGap();
            return;
        }
        if (absoluteDepth++ == 0) absoluteFailed = false;
    }

    public void absoluteChanged(@NotNull IAEKey resource, long amount) {
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
        if (!isProducerThread()) {
            markGap();
            return;
        }
        if (absoluteDepth == 0) throw new IllegalStateException("No stock observation is open");
        absoluteFailed |= !success;
        if (--absoluteDepth != 0) return;
        for (Entry entry : staged) {
            entry.staged = false;
            if (!absoluteFailed) {
                entry.absolute = entry.stagedAmount;
                entry.hasAbsolute = true;
                dirty(entry);
            }
        }
        staged.clear();
        if (absoluteFailed) invalidate();
    }

    /** Native enumeration and metadata remain on the producer; only the returned value may be queued. */
    public @NotNull StorageObservation observe(@NotNull IAEGrid grid, @Nullable IconPack pack, long observedAt,
        boolean full) {
        return observe(grid, pack, observedAt, full, true);
    }

    /** Stock reads may update the live view without draining the independently timed flow bucket. */
    public @NotNull StorageObservation observe(@NotNull IAEGrid grid, @Nullable IconPack pack, long observedAt,
        boolean full, boolean flushFlow) {
        if (!isProducerThread()) throw new IllegalStateException("Native observation requires the producer thread");
        if (absoluteDepth != 0) throw new IllegalStateException("Native stock refresh is incomplete");
        boolean requested = reconcile.getAndSet(false);
        boolean complete = full || requested;
        List<StorageObservation.Row> rows = new ArrayList<>();
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
                for (IAEGenericStack stack : list.web$stacks()) {
                    IAEKey key = stack.web$what();
                    rows.add(row(grid, key, stack.web$amount(), pack));
                    included.add(key);
                }
                for (IAEKey craftable : grid.web$getCraftingGrid()
                    .web$getCraftables(null)) {
                    if (included.add(craftable)) rows.add(row(grid, craftable, 0, pack));
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
                        rows.add(row(grid, identity, amount, pack));
                        included.add(identity);
                    }
                    index++;
                }
                if (scanUnkeyed || hasUnkeyedResources) {
                    for (IAEGenericStack stack : list.web$stacks()) {
                        IAEKey key = stack.web$what();
                        if (needsUnkeyedRead(key) && included.add(key)) {
                            rows.add(row(grid, key, stack.web$amount(), pack));
                        }
                    }
                    for (IAEKey craftable : grid.web$getCraftingGrid()
                        .web$getCraftables(null)) {
                        if (needsUnkeyedRead(craftable) && included.add(craftable))
                            rows.add(row(grid, craftable, 0, pack));
                    }
                }
            }
            List<StorageObservation.Flow> flows = flushFlow ? drainFlow() : Collections.emptyList();
            StorageObservation result = new StorageObservation(
                observedAt,
                complete,
                flushFlow ? !lost.getAndSet(false) : !lost.get(),
                rows,
                flows);
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
        List<StorageObservation.Flow> flows = drainFlow();
        return new StorageObservation(observedAt, false, !lost.getAndSet(false), false, Collections.emptyList(), flows);
    }

    private List<StorageObservation.Flow> drainFlow() {
        // Metadata callbacks may themselves trigger native events. Complete them before draining counters.
        for (int index = 0; index < flowPending.size();) {
            Entry entry = flowPending.get(index);
            IAEKey identity = identity(entry);
            if (identity == null) {
                discard(entry);
                continue;
            }
            if (entry.input != 0 || entry.output != 0) description(entry, identity, null);
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
                flows.add(new StorageObservation.Flow(description(entry, identity, null), entry.input, entry.output));
            }
            entry.input = 0;
            entry.output = 0;
            entry.overflow = false;
            entry.flowPending = false;
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
        }
    }

    private StorageObservation.Row row(IAEGrid grid, IAEKey key, long amount, @Nullable IconPack pack) {
        Entry entry;
        try {
            entry = admit(key);
        } catch (UnsupportedOperationException unsupported) {
            return unkeyedRow(grid, key, amount);
        } catch (IllegalArgumentException invalidIdentity) {
            markGap();
            return unkeyedRow(grid, key, amount);
        }
        if (entry == null) return unkeyedRow(grid, key, amount);
        IAEKey identity = identity(entry);
        if (identity == null) {
            discard(entry);
            return unkeyedRow(grid, key, amount);
        }
        ResourceSnapshot base = description(entry, identity, pack);
        ResourceSnapshot resource = new ResourceSnapshot(
            base.registryNamespace(),
            base.registryPath(),
            base.componentCount(),
            base.damage(),
            base.displayName(),
            base.resourceType(),
            amount,
            base.itemKey(),
            base.iconBaseKey());
        return new StorageObservation.Row(
            resource,
            grid.web$getCraftingGrid()
                .web$isCurrentlyCraftable(key));
    }

    private @Nullable IAEKey identity(Entry entry) {
        try {
            return AE2Controller.itemIdentities.resolve(entry.key);
        } catch (ItemIdentityRegistry.Ambiguous unavailable) {
            return null;
        }
    }

    private void discard(Entry entry) {
        resources.remove(entry.key);
        pending.remove(entry);
        flowPending.remove(entry);
        staged.remove(entry);
        hasUnkeyedResources = true;
        markGap();
    }

    private @NotNull ResourceSnapshot description(Entry entry, IAEKey identity, @Nullable IconPack pack) {
        if (entry.description == null) {
            entry.description = ResourceSnapshot.capture(identity, 0, entry.key, pack);
            entry.iconsChecked = pack != null && pack.find(entry.key) == null;
        }
        if (pack != null && !entry.iconsChecked && pack.find(entry.key) == null) {
            entry.description = ResourceSnapshot.capture(identity, 0, entry.key, pack);
            entry.iconsChecked = true;
        }
        return entry.description;
    }

    private boolean needsUnkeyedRead(IAEKey key) {
        return tryAdmit(key) == null;
    }

    private StorageObservation.Row unkeyedRow(IAEGrid grid, IAEKey key, long amount) {
        hasUnkeyedResources = true;
        return new StorageObservation.Row(ResourceSnapshot.capture(key, amount, null, null), key.web$isCraftable(grid));
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
        private boolean overflow;
        private boolean flowPending;
        private long dirty;
        private boolean pending;
        private boolean hasAbsolute;
        private long absolute;
        private boolean staged;
        private long stagedAmount;
        private @Nullable ResourceSnapshot description;
        private boolean iconsChecked;

        private Entry(StableKey key) {
            this.key = key;
        }
    }
}
