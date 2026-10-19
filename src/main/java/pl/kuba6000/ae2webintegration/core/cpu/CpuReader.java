package pl.kuba6000.ae2webintegration.core.cpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPausableCraftingCPU;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** The single native read of one crafting CPU, shared by the detail response and the live view. Server thread only. */
public final class CpuReader {

    private CpuReader() {}

    /**
     * Reads the header and every resource of the CPU, and replaces the identities the CPU retains. The optional base
     * variant of a resource, only needed for icon fallback, is materialized by the shared description cache when
     * {@code baseKeys} is set; otherwise descriptions are captured without it.
     */
    public static @NotNull CpuObservation read(@NotNull IAEGrid grid, @NotNull ICraftingCPUCluster cpu, long observedAt,
        boolean baseKeys) {
        ItemIdentityRegistry.Listing listing = AE2Controller.itemIdentities.beginListing(cpu);
        boolean busy = cpu.web$isBusy();
        ResourceSnapshot finalOutput = null;
        AE2JobTracker.JobTrackingInfo tracking = null;
        Map<IAEKey, Draft> drafts = new HashMap<>();
        if (busy) {
            finalOutput = ResourceSnapshot.capture(grid, cpu.web$getFinalOutput());
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
        return new CpuObservation(observedAt, true, header, rows, Collections.emptySet(), descriptions, unkeyed);
    }

    /**
     * Reads the header and the given resources from bulk native lists. A resource
     * without a stable identity can only be replaced together with its siblings, and an idle CPU has no resources,
     * so those cases return the full observation instead.
     */
    public static @NotNull CpuObservation read(@NotNull IAEGrid grid, @NotNull ICraftingCPUCluster cpu,
        @NotNull Collection<IAEKey> keys, long observedAt) {
        if (!cpu.web$isBusy()) return read(grid, cpu, observedAt, true);
        AE2JobTracker.JobTrackingInfo tracking = AE2JobTracker.findActiveJob(cpu);
        IStackList active = AE2Controller.AE2Interface.web$createStackList();
        IStackList pending = AE2Controller.AE2Interface.web$createStackList();
        IStackList stored = AE2Controller.AE2Interface.web$createStackList();
        cpu.web$getItemLists(active, pending, stored);
        List<CpuObservation.Row> rows = new ArrayList<>();
        Set<StableKey> missing = new HashSet<>();
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        for (IAEKey key : keys) {
            StableKey itemKey;
            try {
                itemKey = AE2Controller.itemIdentities.remember(cpu, key);
            } catch (RuntimeException conflicted) {
                return read(grid, cpu, observedAt, true);
            }
            if (itemKey == null) return read(grid, cpu, observedAt, true);
            CpuObservation.Amounts amounts = new CpuObservation.Amounts(
                active.web$getAmount(key),
                pending.web$getAmount(key),
                stored.web$getAmount(key),
                tracking == null ? null : tracking(tracking, key));
            if (amounts.active() == 0 && amounts.pending() == 0 && amounts.stored() == 0 && amounts.tracking() == null)
                missing.add(itemKey);
            else {
                rows.add(new CpuObservation.Row(itemKey, amounts));
                descriptions.put(itemKey, ResourceSnapshot.describe(key, itemKey));
            }
        }
        return new CpuObservation(
            observedAt,
            false,
            header(cpu, true, ResourceSnapshot.capture(grid, cpu.web$getFinalOutput()), tracking),
            rows,
            missing,
            descriptions,
            Collections.emptyList());
    }

    private static CpuObservation.Header header(ICraftingCPUCluster cpu, boolean busy,
        @Nullable ResourceSnapshot finalOutput, @Nullable AE2JobTracker.JobTrackingInfo tracking) {
        return new CpuObservation.Header(
            cpu.web$getAvailableStorage(),
            busy,
            cpu.web$getSelectionMode(),
            cpu instanceof IPausableCraftingCPU,
            busy && cpu instanceof IPausableCraftingCPU pausable && pausable.web$isPaused(),
            finalOutput,
            tracking != null,
            tracking == null ? 0 : tracking.timeStarted);
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
