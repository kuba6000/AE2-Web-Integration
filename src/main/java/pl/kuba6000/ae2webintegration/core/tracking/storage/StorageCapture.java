package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IIdentityHolder;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Producer-owned native identities and primitive counters. No native object leaves observe(). */
public final class StorageCapture implements IIdentityHolder {

    private static final int UNSUPPORTED = -2;

    private final Thread producer = Thread.currentThread();
    private final int maxResources;
    private final Map<IAEKey, Entry> resources = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> pending = new ArrayList<>();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean reconcile = new AtomicBoolean(true);
    private long sequence;
    private int absoluteDepth;
    private boolean absoluteFailed;
    private boolean hasUnkeyedResources;
    private final List<Entry> staged = new ArrayList<>();

    public StorageCapture(int maxResources) {
        if (maxResources < 1) throw new IllegalArgumentException("Resource limit must be positive");
        this.maxResources = maxResources;
    }

    public boolean isProducerThread() {
        return Thread.currentThread() == producer;
    }

    /** First-seen admission is cold; repeat admission only looks up native identity. */
    public int prepareResource(@NotNull IAEKey resource) {
        if (!isProducerThread()) {
            markGap();
            return -1;
        }
        try {
            return admit(resource);
        } catch (UnsupportedOperationException unsupported) {
            hasUnkeyedResources = true;
            return UNSUPPORTED;
        } catch (RuntimeException unsupported) {
            markGap();
            return -1;
        }
    }

    private int admit(IAEKey resource) {
        if (!resource.web$supportsStableIdentity()) {
            hasUnkeyedResources = true;
            return UNSUPPORTED;
        }
        Entry existing = resources.get(resource);
        if (existing != null) return existing.unsupported ? UNSUPPORTED : existing.token;
        if (entries.size() >= maxResources) {
            markGap();
            return -1;
        }
        IAEKey retained = resource.web$copyTrackingIdentity();
        Entry entry = new Entry(entries.size(), retained);
        resources.put(retained, entry);
        entries.add(entry);
        return entry.token;
    }

    /** Called only for a completed real operation; rejected and simulated operations add nothing. */
    public void recordFlow(int token, long input, long output) {
        if (input < 0 || output < 0) throw new IllegalArgumentException("Flow must be nonnegative");
        if (input == 0 && output == 0) return;
        if (!isProducerThread()) {
            markGap();
            return;
        }
        if (token == UNSUPPORTED) {
            hasUnkeyedResources = true;
            return;
        }
        if (token < 0 || token >= entries.size()) {
            markGap();
            return;
        }
        Entry entry = entries.get(token);
        entry.input = add(entry, entry.input, input);
        entry.output = add(entry, entry.output, output);
        dirty(entry);
        entry.hasAbsolute = false;
    }

    public void markChanged(@NotNull IAEKey resource) {
        int token = prepareResource(resource);
        if (token < 0) return;
        Entry entry = entries.get(token);
        dirty(entry);
        entry.hasAbsolute = false;
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
        int token = prepareResource(resource);
        if (token < 0) return;
        if (absoluteDepth == 0 || amount < 0) {
            markGap();
            return;
        }
        Entry entry = entries.get(token);
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
        if (!isProducerThread()) throw new IllegalStateException("Native observation requires the producer thread");
        if (absoluteDepth != 0) throw new IllegalStateException("Native stock refresh is incomplete");
        boolean requested = reconcile.getAndSet(false);
        boolean complete = full || requested;
        List<StorageObservation.Row> rows = new ArrayList<>();
        List<StorageObservation.Flow> flows = new ArrayList<>();
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
                for (int index = 0; index < pending.size(); index++) {
                    Entry entry = pending.get(index);
                    if (entry.dirty != 0 && entry.dirty <= through) {
                        long amount = entry.hasAbsolute ? entry.absolute : list.web$getAmount(entry.identity);
                        rows.add(row(grid, entry.identity, amount, pack));
                        included.add(entry.identity);
                    }
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
            // Metadata callbacks may themselves trigger native events. Complete them before draining counters.
            for (int index = 0; index < pending.size(); index++) {
                Entry entry = pending.get(index);
                if (entry.input != 0 || entry.output != 0) description(entry, pack);
            }
            for (Entry entry : pending) {
                if (!entry.unsupported && (entry.input != 0 || entry.output != 0)) {
                    if (entry.overflow) markGap();
                    flows.add(new StorageObservation.Flow(description(entry, pack), entry.input, entry.output));
                }
            }
            StorageObservation result = new StorageObservation(
                observedAt,
                complete,
                !lost.getAndSet(false),
                rows,
                flows);
            for (int index = pending.size() - 1; index >= 0; index--) {
                Entry entry = pending.get(index);
                entry.input = 0;
                entry.output = 0;
                entry.overflow = false;
                if (entry.dirty <= through) {
                    entry.dirty = 0;
                    entry.hasAbsolute = false;
                    entry.pending = false;
                    int last = pending.size() - 1;
                    pending.set(index, pending.get(last));
                    pending.remove(last);
                }
            }
            return result;
        } catch (RuntimeException failure) {
            hasUnkeyedResources |= scanUnkeyed;
            invalidate();
            throw failure;
        }
    }

    private StorageObservation.Row row(IAEGrid grid, IAEKey key, long amount, @Nullable IconPack pack) {
        // Scheduled reads report the original native admission failure; gameplay hooks must instead remain nonthrowing.
        int token;
        try {
            token = admit(key);
        } catch (UnsupportedOperationException unsupported) {
            return unkeyedRow(grid, key, amount);
        }
        if (token == UNSUPPORTED) return unkeyedRow(grid, key, amount);
        if (token < 0) throw new IllegalStateException(
            "Storage resource capacity reached: retained=" + entries.size() + ", limit=" + maxResources);
        ResourceSnapshot base = description(entries.get(token), pack);
        if (base.itemKey() == null) return unkeyedRow(grid, key, amount);
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

    private ResourceSnapshot description(Entry entry, @Nullable IconPack pack) {
        if (entry.description == null) {
            StableKey key;
            try {
                key = AE2Controller.itemIdentities.remember(this, entry.identity);
            } catch (RuntimeException unavailable) {
                // Identity is optional for display. Never persist a row or flow without an exact safe key.
                key = null;
                entry.unsupported = true;
                entry.input = 0;
                entry.output = 0;
                entry.overflow = false;
                hasUnkeyedResources = true;
            }
            entry.description = ResourceSnapshot.capture(entry.identity, 0, key, pack);
        }
        return entry.description;
    }

    private boolean needsUnkeyedRead(IAEKey key) {
        if (!key.web$supportsStableIdentity()) return true;
        Entry entry = resources.get(key);
        return entry == null || entry.unsupported;
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

        private final int token;
        private final IAEKey identity;
        private long input;
        private long output;
        private boolean overflow;
        private long dirty;
        private boolean pending;
        private boolean hasAbsolute;
        private long absolute;
        private boolean staged;
        private long stagedAmount;
        private @Nullable ResourceSnapshot description;
        private boolean unsupported;

        private Entry(int token, IAEKey identity) {
            this.token = token;
            this.identity = identity;
        }
    }
}
