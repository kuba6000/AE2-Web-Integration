package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IPausableCraftingCPU;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

/** Fake native crafting CPUs with settable work, shared by the CPU reader, live stream and HTTP tests. */
final class CpuFixtures {

    private CpuFixtures() {}

    @SuppressWarnings("BusyWait") // Bounded polling is required for GC, which has no completion callback.
    static void awaitCollected(WeakReference<?> identity) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (identity.get() != null && System.nanoTime() < deadline) {
            System.gc();
            AE2Controller.itemIdentities.cleanUp();
            Thread.sleep(10);
        }
        assertNull(identity.get(), "An obsolete CPU identity must be reclaimable while the CPU survives");
    }

    /** A native resource whose identity is its name; an unstable one models an unsupported variant. */
    static final class Resource implements IAEKey {

        final String name;
        final boolean stable;
        /** Counts base variant normalizations across all copies of this identity. */
        final AtomicInteger baseKeyReads;

        Resource(String name) {
            this(name, true);
        }

        Resource(String name, boolean stable) {
            this(name, stable, new AtomicInteger());
        }

        private Resource(String name, boolean stable, AtomicInteger baseKeyReads) {
            this.name = name;
            this.stable = stable;
            this.baseKeyReads = baseKeyReads;
        }

        @Override
        public boolean web$supportsStableIdentity() {
            return stable;
        }

        public @NotNull ResourceType web$getResourceType() {
            return ResourceType.ITEM;
        }

        public @Nullable StableKey web$getKey() {
            return stable ? StableKey.create(sink -> sink.putBytes(name.getBytes(StandardCharsets.UTF_8))) : null;
        }

        public @NotNull IAEKey web$copyIdentity() {
            return new Resource(name, stable, baseKeyReads);
        }

        @Override
        public @NotNull StableKey web$getBaseKey() {
            baseKeyReads.incrementAndGet();
            return StableKey.create(sink -> sink.putBytes(("base:" + name).getBytes(StandardCharsets.UTF_8)));
        }

        public @NotNull String web$getRegistryNamespace() {
            return "test";
        }

        public @NotNull String web$getRegistryPath() {
            return name;
        }

        public int web$getComponentCount() {
            return 0;
        }

        public int web$getDamage() {
            return 0;
        }

        public @NotNull String web$getDisplayName() {
            return "Display " + name;
        }

        public boolean web$isCraftable(IAEGrid grid) {
            return false;
        }

        StableKey key() {
            return web$getKey();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Resource resource && resource.name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    static final class Stack implements IAEGenericStack {

        private final IAEKey key;
        private final long amount;

        Stack(IAEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        public @NotNull IAEKey web$what() {
            return key;
        }

        public long web$amount() {
            return amount;
        }
    }

    /** A collectable stack list, as the native adapters create and fill one. */
    static final class StackList implements IStackList {

        final Map<IAEKey, Long> amounts = new LinkedHashMap<>();

        public long web$getAmount(IAEKey key) {
            return amounts.getOrDefault(key, 0L);
        }

        public Iterable<IAEGenericStack> web$stacks() {
            List<IAEGenericStack> stacks = new ArrayList<>();
            for (Map.Entry<IAEKey, Long> entry : amounts.entrySet())
                stacks.add(new Stack(entry.getKey(), entry.getValue()));
            return stacks;
        }
    }

    static class Cpu implements ICraftingCPUCluster {

        final StableKey key;
        final Map<IAEKey, long[]> work = new LinkedHashMap<>();
        final StackList waiting = new StackList();
        boolean busy;
        boolean valid = true;
        @Nullable
        Resource output;
        long outputAmount = 1;
        @Nullable
        CpuSelectionMode selectionMode;
        int bulkReads;
        int busyReads;

        Cpu(long id) {
            key = TestGridFixtures.key(id);
        }

        /** Sets active, pending and stored units; all zero removes the resource from the CPU. */
        Cpu set(Resource resource, long active, long pending, long stored) {
            if (active == 0 && pending == 0 && stored == 0) work.remove(resource);
            else work.put(resource, new long[] { active, pending, stored });
            return this;
        }

        public @NotNull StableKey web$getKey() {
            return key;
        }

        public boolean web$isValidForGrid(@NotNull IAEGrid grid) {
            return valid && grid instanceof Grid current && current.cpus.contains(this);
        }

        public String web$getName() {
            return "cpu";
        }

        public long web$getAvailableStorage() {
            return 64;
        }

        public long web$getUsedStorage() {
            return 0;
        }

        public long web$getCoProcessors() {
            return 0;
        }

        public boolean web$isBusy() {
            busyReads++;
            return busy;
        }

        public boolean web$acceptsPlayerJobs() {
            return !busy;
        }

        public @Nullable CpuSelectionMode web$getSelectionMode() {
            return selectionMode;
        }

        public void web$cancel() {
            busy = false;
        }

        public IAEGenericStack web$getFinalOutput() {
            return output == null ? null : new Stack(output, outputAmount);
        }

        public void web$getItemLists(@NotNull IStackList active, @NotNull IStackList pending,
            @NotNull IStackList stored) {
            bulkReads++;
            for (Map.Entry<IAEKey, long[]> entry : work.entrySet()) {
                long[] units = entry.getValue();
                if (units[0] != 0) ((StackList) active).amounts.put(entry.getKey(), units[0]);
                if (units[1] != 0) ((StackList) pending).amounts.put(entry.getKey(), units[1]);
                if (units[2] != 0) ((StackList) stored).amounts.put(entry.getKey(), units[2]);
            }
        }

        public IStackList web$getWaitingFor() {
            return waiting;
        }
    }

    static final class PausableCpu extends Cpu implements IPausableCraftingCPU {

        boolean paused;

        PausableCpu(long id) {
            super(id);
        }

        public boolean web$isPaused() {
            return paused;
        }

        public void web$setPaused(boolean value) {
            paused = value;
        }
    }

    /** A grid whose crafting service lists a mutable set of CPUs. */
    static final class Grid extends TestGridFixtures.TestGrid {

        final Set<ICraftingCPUCluster> cpus = new LinkedHashSet<>();
        int cpuListReads;

        Grid(long id, Cpu... initial) {
            super(id, false, AEControllerState.CONTROLLER_ONLINE);
            Collections.addAll(cpus, initial);
        }

        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return (IAECraftingGrid) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IAECraftingGrid.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "web$getCPUs" -> {
                        cpuListReads++;
                        yield cpus;
                    }
                    case "web$getCPUCount" -> cpus.size();
                    default -> throw new AssertionError("Unexpected crafting operation: " + method.getName());
                });
        }
    }

    /** The native entry point that creates the stack lists the CPU fills. */
    static final class Ae extends TestGridFixtures.TestAE {

        Ae(IAEGrid... grids) {
            super(grids);
        }

        @Override
        public IStackList web$createStackList() {
            return new StackList();
        }
    }

    @Desugar
    record Event(String name, JsonObject data) {}

    /** The next event of an SSE stream, skipping heartbeats. */
    static @NotNull Event readEvent(BufferedReader input) throws Exception {
        String name = null;
        JsonObject data = null;
        for (String line; (line = input.readLine()) != null;) {
            if (line.startsWith("event: ")) name = line.substring(7);
            if (line.startsWith("data: ")) data = new JsonParser().parse(line.substring(6))
                .getAsJsonObject();
            if (line.isEmpty() && name != null) {
                if (!name.equals("heartbeat")) return new Event(name, data);
                name = null;
                data = null;
            }
        }
        return fail("Stream ended before its next event");
    }
}
