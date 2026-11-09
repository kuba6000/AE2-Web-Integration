package pl.kuba6000.ae2webintegration.core.cpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IIdentityHolder;
import pl.kuba6000.ae2webintegration.core.interfaces.IPausableCraftingCPU;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/**
 * The native reads of crafting CPUs shared by the endpoints and the live view: the CPUs of a grid by address, and the
 * single read of one CPU. Server thread only.
 */
public final class CpuReader {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    private CpuReader() {}

    /** Duplicate addresses are logged; the last CPU for an address is retained. */
    public static @NotNull Map<StableKey, ICraftingCPUCluster> list(@NotNull IAECraftingGrid craftingGrid) {
        Map<StableKey, ICraftingCPUCluster> orderedMap = new LinkedHashMap<>();
        for (ICraftingCPUCluster cpu : craftingGrid.web$getCPUs()) {
            StableKey id = cpu.web$getKey();
            ICraftingCPUCluster previous = orderedMap.put(id, cpu);
            if (previous != null) {
                LOG.error(
                    "Duplicate crafting CPU ID '{}' for '{}' and '{}'; retaining the last CPU",
                    id,
                    previous.web$getName(),
                    cpu.web$getName());
            }
        }
        return orderedMap;
    }

    /**
     * Reads the header and every resource of the CPU, replacing identities retained until its next job boundary. The
     * optional base
     * variant of a resource, only needed for icon fallback, is materialized by the shared description cache when
     * {@code baseKeys} is set; otherwise descriptions are captured without it.
     */
    public static @NotNull CpuObservation read(@NotNull ICraftingCPUCluster cpu, boolean baseKeys) {
        return read(cpu, cpu, baseKeys);
    }

    static @NotNull CpuObservation read(@NotNull IIdentityHolder owner, @NotNull ICraftingCPUCluster cpu,
        boolean baseKeys) {
        ItemIdentityRegistry.Listing listing = AE2Controller.itemIdentities.beginListing(owner);
        boolean busy = cpu.web$isBusy();
        ResourceSnapshot finalOutput = null;
        AE2JobTracker.JobTrackingInfo tracking = null;
        Map<IAEKey, Draft> drafts = new HashMap<>();
        if (busy) {
            IAEGenericStack output = cpu.web$getFinalOutput();
            if (output != null) {
                Draft outputDraft = draft(output.web$what(), listing, true);
                finalOutput = new ResourceSnapshot(outputDraft.description, output.web$amount());
            }
            tracking = AE2JobTracker.findActiveJob(cpu);
            IStackList active = AE2Controller.AE2Interface.web$createStackList();
            IStackList pending = AE2Controller.AE2Interface.web$createStackList();
            IStackList stored = AE2Controller.AE2Interface.web$createStackList();
            cpu.web$getItemLists(active, pending, stored);
            for (IAEGenericStack stack : active.web$stacks())
                drafts.computeIfAbsent(stack.web$what(), resource -> draft(resource, listing, baseKeys)).active += stack
                    .web$amount();
            for (IAEGenericStack stack : pending.web$stacks()) drafts
                .computeIfAbsent(stack.web$what(), resource -> draft(resource, listing, baseKeys)).pending += stack
                    .web$amount();
            for (IAEGenericStack stack : stored.web$stacks())
                drafts.computeIfAbsent(stack.web$what(), resource -> draft(resource, listing, baseKeys)).stored += stack
                    .web$amount();
            if (tracking != null) {
                for (IAEKey key : tracking.timeSpentOn.keySet()) {
                    drafts.computeIfAbsent(key, resource -> draft(resource, listing, baseKeys)).tracking = tracking(
                        tracking,
                        key);
                }
            }
        }
        CpuObservation.Header header = header(cpu, busy, finalOutput, tracking);
        List<CpuObservation.Row> rows = new ArrayList<>();
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        List<CpuObservation.UnkeyedRow> unkeyed = new ArrayList<>();
        for (Draft draft : drafts.values()) {
            CpuObservation.Amounts amounts = draft.amounts();
            if (draft.itemKey == null) unkeyed.add(new CpuObservation.UnkeyedRow(draft.description, amounts));
            else {
                rows.add(new CpuObservation.Row(draft.itemKey, amounts));
                descriptions.put(draft.itemKey, draft.description);
            }
        }
        listing.commit();
        return new CpuObservation(true, header, rows, Collections.emptySet(), descriptions, unkeyed);
    }

    /**
     * Reads the header and the given resources from bulk native lists. A resource
     * without a stable identity can only be replaced together with its siblings, and an idle CPU has no resources,
     * so those cases return the full observation instead.
     */
    public static @NotNull CpuObservation read(@NotNull IIdentityHolder owner, @NotNull ICraftingCPUCluster cpu,
        @NotNull Collection<IAEKey> keys) {
        if (!cpu.web$isBusy()) return read(owner, cpu, true);
        Map<IAEKey, StableKey> itemKeys = new LinkedHashMap<>();
        for (IAEKey key : keys) {
            StableKey itemKey;
            try {
                itemKey = AE2Controller.itemIdentities.remember(owner, key);
            } catch (RuntimeException conflicted) {
                return read(owner, cpu, true);
            }
            if (itemKey == null) return read(owner, cpu, true);
            itemKeys.put(key, itemKey);
        }
        AE2JobTracker.JobTrackingInfo tracking = AE2JobTracker.findActiveJob(cpu);
        IStackList active = AE2Controller.AE2Interface.web$createStackList();
        IStackList pending = AE2Controller.AE2Interface.web$createStackList();
        IStackList stored = AE2Controller.AE2Interface.web$createStackList();
        cpu.web$getItemLists(active, pending, stored);
        List<CpuObservation.Row> rows = new ArrayList<>();
        Set<StableKey> missing = new HashSet<>();
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        ResourceSnapshot finalOutput = readOutput(owner, cpu.web$getFinalOutput());
        for (Map.Entry<IAEKey, StableKey> entry : itemKeys.entrySet()) {
            IAEKey key = entry.getKey();
            StableKey itemKey = entry.getValue();
            CpuObservation.Amounts amounts = new CpuObservation.Amounts(
                active.web$getAmount(key),
                pending.web$getAmount(key),
                stored.web$getAmount(key),
                tracking == null ? null : tracking(tracking, key));
            if (amounts.active() == 0 && amounts.pending() == 0
                && amounts.stored() == 0
                && amounts.tracking() == null) {
                missing.add(itemKey);
                if (finalOutput == null || !itemKey.equals(
                    finalOutput.description()
                        .itemKey()))
                    AE2Controller.itemIdentities.release(owner, itemKey);
            } else {
                rows.add(new CpuObservation.Row(itemKey, amounts));
                descriptions.put(itemKey, ResourceSnapshot.describe(key, itemKey));
            }
        }
        return new CpuObservation(
            false,
            header(cpu, true, finalOutput, tracking),
            rows,
            missing,
            descriptions,
            Collections.emptyList());
    }

    /** Reads only detached CPU metadata, without enumerating resource lists. Server thread only. */
    static @NotNull CpuObservation.Header readHeader(@NotNull IIdentityHolder owner, @NotNull ICraftingCPUCluster cpu) {
        boolean busy = cpu.web$isBusy();
        return header(
            cpu,
            busy,
            busy ? readOutput(owner, cpu.web$getFinalOutput()) : null,
            busy ? AE2JobTracker.findActiveJob(cpu) : null);
    }

    /** Retains the current output for subsequent actions under its job or live-view owner. */
    public static @Nullable ResourceSnapshot readOutput(@NotNull IIdentityHolder owner,
        @Nullable IAEGenericStack output) {
        if (output == null) return null;
        IAEKey resource = output.web$what();
        StableKey itemKey;
        try {
            itemKey = AE2Controller.itemIdentities.remember(owner, resource);
        } catch (RuntimeException unavailable) {
            itemKey = null;
        }
        return ResourceSnapshot.capture(resource, output.web$amount(), itemKey);
    }

    private static CpuObservation.Header header(ICraftingCPUCluster cpu, boolean busy,
        @Nullable ResourceSnapshot finalOutput, @Nullable AE2JobTracker.JobTrackingInfo tracking) {
        List<CraftingRequester> requesters = tracking == null ? Collections.emptyList() : tracking.getRequesters();
        return new CpuObservation.Header(
            cpu.web$getAvailableStorage(),
            busy,
            cpu.web$getSelectionMode(),
            cpu instanceof IPausableCraftingCPU,
            busy && cpu instanceof IPausableCraftingCPU pausable && pausable.web$isPaused(),
            finalOutput,
            tracking != null,
            tracking == null ? 0 : tracking.timeStarted,
            requesters,
            nativeRequesterName(cpu, busy, requesters));
    }

    /** Reads optional native attribution only for busy jobs without tracked requesters. Server thread only. */
    public static @Nullable String nativeRequesterName(@NotNull ICraftingCPUCluster cpu, boolean busy,
        @NotNull List<CraftingRequester> requesters) {
        return busy && requesters.isEmpty() ? cpu.web$getRequesterName() : null;
    }

    private static @Nullable CpuObservation.Tracking tracking(AE2JobTracker.JobTrackingInfo info, IAEKey key) {
        Long completed = info.timeSpentOn.get(key);
        if (completed == null) return null;
        Long waitingSince = info.startedWaitingFor.get(key);
        return new CpuObservation.Tracking(
            info.craftedTotal.getOrDefault(key, 0L),
            completed,
            waitingSince == null ? 0 : waitingSince);
    }

    private static Draft draft(IAEKey resource, ItemIdentityRegistry.Listing listing, boolean baseKeys) {
        StableKey itemKey = null;
        try {
            itemKey = listing.remember(resource);
        } catch (RuntimeException unavailable) {
            // An optional identity failure must not hide the resource's work; it is listed without a key.
        }
        return new Draft(
            itemKey,
            baseKeys ? ResourceSnapshot.describe(resource, itemKey)
                : ResourceDescriptor.capture(resource, itemKey, null));
    }

    private static final class Draft {

        private final @Nullable StableKey itemKey;
        private final ResourceDescriptor description;
        private long active;
        private long pending;
        private long stored;
        private @Nullable CpuObservation.Tracking tracking;

        private Draft(@Nullable StableKey itemKey, ResourceDescriptor description) {
            this.itemKey = itemKey;
            this.description = description;
        }

        private CpuObservation.Amounts amounts() {
            return new CpuObservation.Amounts(active, pending, stored, tracking);
        }
    }
}
