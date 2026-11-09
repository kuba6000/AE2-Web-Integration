package pl.kuba6000.ae2webintegration.core.cpu;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridFilter;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IIdentityHolder;
import pl.kuba6000.ae2webintegration.core.live.LiveTopic;

/**
 * Server-thread observation of the crafting CPUs that have viewers. Native change notifications mark resources of
 * a viewed CPU dirty; they are read at the storage view's active cadence and published once for all viewers.
 * Metadata changes read only the header. Idle views validate native membership without enumerating resources.
 * A CPU nobody views holds no state and is never read. Independent of storage tracking and history.
 */
public final class CpuLiveCoordinator {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final long VALIDATION_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private final CpuRegistry cpus;
    private final GridIdentityRegistry identities;
    private ConfigSettings.ResourceTracking settings;
    private final Map<LiveTopic.Key, Binding> bindings = new HashMap<>();
    // Native notifications find their CPU by instance: no hashing of native objects, no identity resolution.
    private final Map<ICraftingCPUCluster, Binding> byCpu = new IdentityHashMap<>();
    private long generation = System.currentTimeMillis();

    public CpuLiveCoordinator(@NotNull CpuRegistry cpus, @NotNull GridIdentityRegistry identities,
        @NotNull ConfigSettings.ResourceTracking settings) {
        this.cpus = cpus;
        this.identities = identities;
        this.settings = settings;
    }

    /** Called on the server thread; subscribers and dirty native resource identities remain intact. */
    public void applySettings(@NotNull ConfigSettings.ResourceTracking updated, long nowMillis) {
        if (settings == updated) return;
        for (Binding binding : bindings.values())
            binding.nextRead = Math.min(binding.nextRead, nowMillis + updated.activeIntervalMillis);
        settings = updated;
    }

    /**
     * Starts or refreshes observation of a CPU for a viewer about to reserve a subscription, and reads its current
     * state so that the snapshot is as fresh as a plain detail request. A different CPU object or grid under the same
     * address ends the previous view.
     */
    public void prepareForViewer(@NotNull StableKey gridKey, @NotNull IAEGrid grid, @NotNull StableKey cpuKey,
        @NotNull ICraftingCPUCluster cpu, long nowMillis) {
        LiveTopic.Key key = CpuTopic.keyOf(gridKey, cpuKey);
        Binding binding = bindings.get(key);
        if (binding != null && (binding.cpu != cpu || binding.grid.get() != grid)) {
            retire(binding);
            binding = null;
        }
        if (binding == null) {
            binding = new Binding(key, gridKey, cpuKey, ++generation, grid, cpu);
            bindings.put(key, binding);
            byCpu.put(cpu, binding);
        }
        cpus.activate(gridKey, cpuKey, binding.generation, nowMillis);
        if (!present(grid, binding)) {
            retire(binding);
            return;
        }
        binding.nextValidation = nowMillis + VALIDATION_MILLIS;
        publish(binding, CpuReader.read(binding, cpu, true), nowMillis);
    }

    /** Native hook: a resource of this CPU changed. Costs one identity lookup, and nothing for unviewed CPUs. */
    public void dirty(@NotNull ICraftingCPUCluster cpu, @NotNull IAEKey resource) {
        Binding binding = byCpu.get(cpu);
        if (binding != null) binding.dirty.add(resource);
    }

    /** Successful scheduling can change pending outputs without inserting them into the native waiting list. */
    public void pushedPattern(@NotNull ICraftingCPUCluster cpu, @NotNull IAECraftingPatternDetails details) {
        Binding binding = byCpu.get(cpu);
        if (binding == null) return;
        for (IAEGenericStack output : details.web$getCondensedOutputs()) binding.dirty.add(output.web$what());
    }

    /** A metadata notification schedules only a header read; unviewed CPUs cost nothing. */
    public void metadataChanged(@NotNull ICraftingCPUCluster cpu) {
        Binding binding = byCpu.get(cpu);
        if (binding != null) binding.metadataDirty = true;
    }

    /** Native hook: a job started, merged, completed or was cancelled. Viewers fetch a fresh snapshot. */
    public void jobChanged(@NotNull ICraftingCPUCluster cpu) {
        Binding binding = byCpu.get(cpu);
        if (binding != null) retire(binding);
    }

    /** Releases CPUs without viewers or grid, then performs at most one read, so a tick cost stays bounded. */
    public void tick(long nowMillis) {
        if (bindings.isEmpty()) return;
        Set<LiveTopic.Key> viewing = cpus.viewed(nowMillis);
        Binding due = null;
        IAEGrid dueGrid = null;
        for (Iterator<Binding> iterator = bindings.values()
            .iterator(); iterator.hasNext();) {
            Binding binding = iterator.next();
            IAEGrid grid = binding.grid.get();
            if (!viewing.contains(binding.key) || grid == null || identities.getGrid(binding.gridKey) != grid) {
                iterator.remove();
                release(binding);
            } else if (binding.due(nowMillis) && (due == null || binding.lastRead < due.lastRead)) {
                due = binding;
                dueGrid = grid;
            }
        }
        if (due != null) observe(due, dueGrid, nowMillis);
    }

    private void observe(Binding binding, IAEGrid grid, long nowMillis) {
        binding.lastRead = nowMillis;
        try {
            if (!present(grid, binding)) {
                retire(binding);
                return;
            }
            binding.nextValidation = nowMillis + VALIDATION_MILLIS;
            if (!binding.dirty.isEmpty() && nowMillis >= binding.nextRead) {
                CpuObservation observation = CpuReader.read(binding, binding.cpu, binding.dirty);
                binding.dirty.clear();
                publish(binding, observation, nowMillis);
            } else if (binding.metadataDirty) {
                cpus.observed(
                    binding.gridKey,
                    binding.cpuKey,
                    binding.generation,
                    new CpuObservation(
                        false,
                        CpuReader.readHeader(binding, binding.cpu),
                        Collections.emptyList(),
                        Collections.emptySet(),
                        Collections.emptyMap(),
                        Collections.emptyList()));
                binding.metadataDirty = false;
            }
        } catch (RuntimeException failure) {
            LOG.warn("Crafting CPU {} could not be observed", binding.cpuKey, failure);
            cpus.invalidate(binding.gridKey, binding.cpuKey, binding.generation);
        }
    }

    private void publish(Binding binding, CpuObservation observation, long nowMillis) {
        if (observation.full()) {
            binding.dirty.clear();
        }
        binding.metadataDirty = false;
        binding.nextRead = nowMillis + settings.activeIntervalMillis;
        cpus.observed(binding.gridKey, binding.cpuKey, binding.generation, observation);
    }

    /** Native validity and exact grid ownership are checked without enumerating CPU or resource lists. */
    private static boolean present(IAEGrid grid, Binding binding) {
        return GridFilter.isUsable(grid) && binding.cpu.web$isValidForGrid(grid);
    }

    private void retire(Binding binding) {
        bindings.remove(binding.key);
        release(binding);
    }

    private void release(Binding binding) {
        // A surviving CPU may already be indexed by a newer binding; only remove this binding's own entry.
        byCpu.remove(binding.cpu, binding);
        AE2Controller.itemIdentities.release(binding);
        binding.dirty.clear();
        cpus.retire(binding.gridKey, binding.cpuKey, binding.generation);
    }

    private static final class Binding implements IIdentityHolder {

        private final LiveTopic.Key key;
        private final StableKey gridKey;
        private final StableKey cpuKey;
        private final long generation;
        private final WeakReference<IAEGrid> grid;
        private final ICraftingCPUCluster cpu;
        private final Set<IAEKey> dirty = new HashSet<>();
        private long nextRead;
        private boolean metadataDirty;
        private long nextValidation;
        private long lastRead;

        private Binding(LiveTopic.Key key, StableKey gridKey, StableKey cpuKey, long generation, IAEGrid grid,
            ICraftingCPUCluster cpu) {
            this.key = key;
            this.gridKey = gridKey;
            this.cpuKey = cpuKey;
            this.generation = generation;
            this.grid = new WeakReference<>(grid);
            this.cpu = cpu;
        }

        private boolean due(long nowMillis) {
            return nowMillis >= nextValidation || metadataDirty || !dirty.isEmpty() && nowMillis >= nextRead;
        }
    }
}
