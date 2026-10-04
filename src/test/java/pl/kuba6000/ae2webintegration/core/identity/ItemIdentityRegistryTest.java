package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IIdentityHolder;

@SuppressWarnings({ "UnstableApiUsage", "PMD.AvoidMagicNumbers" })
class ItemIdentityRegistryTest {

    @Test
    void cpuAndInventorySnapshotsRetainIndependentResourcesAndShareBaseMemo() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IIdentityHolder grid = grid();
        IIdentityHolder cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            ICraftingCPUCluster.class.getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> null);
        java.util.concurrent.atomic.AtomicInteger normalizations = new java.util.concurrent.atomic.AtomicInteger();
        StableKey base = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        Resource resource = new Resource("worn", false) {

            @Override
            public @NotNull IAEKey web$copyIdentity() {
                return this;
            }

            @Override
            public StableKey web$getIconBaseKey() {
                normalizations.incrementAndGet();
                return base;
            }
        };
        StableKey key = registry.remember(grid, resource);
        assertEquals(0, normalizations.get());
        ItemIdentityRegistry.Listing cpuListing = registry.beginListing(cpu);
        assertEquals(key, cpuListing.remember(resource));
        cpuListing.commit();
        assertEquals(base, registry.resolveIconBase(key));
        registry.beginListing(grid)
            .commit();
        System.gc();
        assertSame(resource, registry.resolve(key));
        assertEquals(base, registry.resolveIconBase(key));
        assertEquals(1, normalizations.get());
    }

    @Test
    void unsupportedBaseIsMemoizedUntilWorldCleanup() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        Resource resource = new Resource("unsupported", false) {

            @Override
            public @NotNull IAEKey web$copyIdentity() {
                return this;
            }

            @Override
            public StableKey web$getIconBaseKey() {
                attempts.incrementAndGet();
                return null;
            }
        };
        StableKey key = registry.remember(grid, resource);
        assertNull(registry.resolveIconBase(key));
        assertNull(registry.resolveIconBase(key));
        assertEquals(1, attempts.get());
        registry.clear();
        registry.remember(grid, resource);
        assertNull(registry.resolveIconBase(key));
        assertEquals(2, attempts.get());
    }

    @Test
    void nativeNormalizationFailureIsOptionalAndMemoized() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        Resource resource = new Resource("broken-normalizer", false) {

            public @NotNull IAEKey web$copyIdentity() {
                return this;
            }

            public StableKey web$getIconBaseKey() {
                attempts.incrementAndGet();
                throw new IllegalStateException("Native capability cannot normalize");
            }
        };
        StableKey key = registry.remember(grid, resource);
        assertNull(registry.resolveIconBase(key));
        assertNull(registry.resolveIconBase(key));
        assertSame(resource, registry.resolve(key));
        assertEquals(1, attempts.get());
    }

    @Test
    void immutableNativeIdentityIsHashedOnlyOnceOnAdmission() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        Resource immutable = new Resource("stone", false) {

            @Override
            public @NotNull IAEKey web$copyIdentity() {
                return this;
            }
        };
        StableKey key = registry.remember(grid, immutable);
        assertSame(immutable, registry.resolve(key));
        assertEquals(1, immutable.encodings);
        assertEquals(key, registry.remember(grid, immutable));
    }

    @Test
    void equivalentResourcesShareOneDetachedIdentityAcrossGrids() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid firstGrid = grid();
        IAEGrid secondGrid = grid();
        Resource first = new Resource("iron", true);
        StableKey key = registry.remember(firstGrid, first);
        IAEKey copy = registry.resolve(key);
        assertNotNull(copy);
        Resource nextPoll = new Resource("iron", false);
        assertEquals(key, registry.remember(secondGrid, nextPoll));
        assertSame(copy, registry.resolve(key));
        assertNotSame(first, copy);
        assertFalse(copy.web$isCraftable(null));
        assertEquals(0, first.encodings);
        assertEquals(1, ((Resource) copy).encodings);
        assertEquals(0, nextPoll.encodings);
        registry.beginListing(firstGrid)
            .commit();
        System.gc();
        assertSame(copy, registry.resolve(key));
        assertEquals(key, registry.remember(secondGrid, nextPoll));
        assertEquals(0, nextPoll.encodings);
    }

    @Test
    void refreshingAListingReusesItsIdentitiesAndReleasesRemovedResources() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        StableKey iron = registry.remember(grid, new Resource("iron", true));
        String ironToken = iron.toString();
        StableKey gold = registry.remember(grid, new Resource("gold", true));
        WeakReference<IAEKey> removed = new WeakReference<>(registry.resolve(gold));
        Resource nextPoll = new Resource("iron", false);
        ItemIdentityRegistry.Listing listing = registry.beginListing(grid);
        assertEquals(iron, listing.remember(nextPoll));
        assertSame(
            ironToken,
            listing.remember(nextPoll)
                .toString());
        assertEquals(0, nextPoll.encodings);
        listing.commit();
        awaitCollected(removed, () -> registry.resolve(gold));
        assertNull(registry.resolve(gold));
        assertNotNull(registry.resolve(iron));
        assertEquals(iron, registry.remember(grid, nextPoll));
    }

    @Test
    void unfinishedListingKeepsThePreviouslyPublishedResources() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        StableKey iron = registry.remember(grid, new Resource("iron", true));
        registry.beginListing(grid)
            .remember(new Resource("gold", true));
        System.gc();
        assertNotNull(registry.resolve(iron));
        assertEquals(iron, registry.remember(grid, new Resource("iron", false)));
    }

    @Test
    void unreachableGridDoesNotKeepItsIdentitiesAlive() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        StableKey key = StableKey.create(sink -> sink.putBytes("iron".getBytes(StandardCharsets.UTF_8)));
        WeakReference<IAEGrid> owner = rememberTemporaryGrid(registry);
        WeakReference<IAEKey> identity = new WeakReference<>(registry.resolve(key));
        awaitCollected(owner, () -> registry.resolve(key));
        awaitCollected(identity, () -> registry.resolve(key));
        assertNull(registry.resolve(key));
    }

    @Test
    void gridAndRegistryClearDoNotChangeDeterministicIds() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        StableKey key = registry.remember(grid, new Resource("iron", true));
        registry.clear();
        assertNull(registry.resolve(key));
        assertEquals(key, registry.remember(grid, new Resource("iron", false)));
    }

    @Test
    void observedConflictCannotBeReusedThroughEitherGridOrTheWarmIndex() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid first = grid();
        IAEGrid second = grid();
        StableKey key = registry.remember(first, new Resource("first", false, "same bytes"));
        assertThrows(
            ItemIdentityRegistry.Ambiguous.class,
            () -> registry.remember(second, new Resource("second", false, "same bytes")));
        assertThrows(ItemIdentityRegistry.Ambiguous.class, () -> registry.resolve(key));
        assertThrows(
            ItemIdentityRegistry.Ambiguous.class,
            () -> registry.remember(first, new Resource("first", false, "same bytes")));
        registry.beginListing(first)
            .commit();
        registry.beginListing(second)
            .commit();
        assertThrows(ItemIdentityRegistry.Ambiguous.class, () -> registry.resolve(key));
    }

    @Test
    void completedListingCannotBeReusedToRestoreAnObsoleteSnapshot() {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        ItemIdentityRegistry.Listing listing = registry.beginListing(grid);
        listing.remember(new Resource("iron", true));
        listing.commit();
        assertThrows(IllegalStateException.class, listing::commit);
        assertThrows(IllegalStateException.class, () -> listing.remember(new Resource("gold", true)));
    }

    private static WeakReference<IAEGrid> rememberTemporaryGrid(ItemIdentityRegistry registry) {
        IAEGrid grid = grid();
        registry.remember(grid, new Resource("iron", true));
        return new WeakReference<>(grid);
    }

    @SuppressWarnings("BusyWait") // GC has no completion callback; polling is bounded by the deadline.
    private static void awaitCollected(WeakReference<?> reference, Runnable maintenance) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc();
            maintenance.run();
            Thread.sleep(10);
        }
        assertNull(reference.get(), "Registry must not retain an unowned grid or resource");
    }

    private static IAEGrid grid() {
        return (IAEGrid) Proxy.newProxyInstance(
            IAEGrid.class.getClassLoader(),
            new Class<?>[] { IAEGrid.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "TestGrid@" + Integer.toHexString(System.identityHashCode(proxy));
                default -> null;
            });
    }

    static class Resource implements IAEKey {

        final String name;
        final boolean craftable;
        final String encodedName;
        int encodings;

        Resource(String name, boolean craftable) {
            this(name, craftable, name);
        }

        Resource(String name, boolean craftable, String encodedName) {
            this.name = name;
            this.craftable = craftable;
            this.encodedName = encodedName;
        }

        @Override
        public @NotNull StableKey web$getKey() {
            encodings++;
            return StableKey.create(sink -> sink.putBytes(encodedName.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public @NotNull IAEKey web$copyIdentity() {
            return new Resource(name, false, encodedName);
        }

        @Override
        public @NotNull String web$getItemID() {
            return name;
        }

        @Override
        public @NotNull String web$getDisplayName() {
            return name;
        }

        @Override
        public boolean web$isCraftable(IAEGrid grid) {
            return craftable;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Resource && name.equals(((Resource) other).name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }
}
