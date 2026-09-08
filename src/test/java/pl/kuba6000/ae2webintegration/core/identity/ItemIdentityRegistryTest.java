package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

class ItemIdentityRegistryTest {

    @Test
    void immutableNativeIdentityIsHashedOnlyOnceOnAdmission() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        Resource immutable = new Resource("stone", false) {

            @Override
            public IAEKey web$copyIdentity() {
                return this;
            }
        };
        StableKey key = registry.remember(grid, immutable);
        assertSame(immutable, registry.resolve(key));
        assertEquals(1, immutable.encodings);
        assertEquals(key, registry.remember(grid, immutable));
    }

    @Test
    void equivalentResourcesShareOneDetachedIdentityAcrossGrids() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid firstGrid = grid();
        IAEGrid secondGrid = grid();
        Resource first = new Resource("iron", true);
        StableKey key = registry.remember(firstGrid, first);
        IAEKey copy = registry.resolve(key);
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
    void unfinishedListingKeepsThePreviouslyPublishedResources() throws Exception {
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
    void gridAndRegistryClearDoNotChangeDeterministicIds() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        StableKey key = registry.remember(grid, new Resource("iron", true));
        registry.clear();
        assertNull(registry.resolve(key));
        assertEquals(key, registry.remember(grid, new Resource("iron", false)));
    }

    @Test
    void observedConflictCannotBeReusedThroughEitherGridOrTheWarmIndex() throws Exception {
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
    void completedListingCannotBeReusedToRestoreAnObsoleteSnapshot() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry();
        IAEGrid grid = grid();
        ItemIdentityRegistry.Listing listing = registry.beginListing(grid);
        listing.remember(new Resource("iron", true));
        listing.commit();
        assertThrows(IllegalStateException.class, listing::commit);
        assertThrows(IllegalStateException.class, () -> listing.remember(new Resource("gold", true)));
    }

    private static WeakReference<IAEGrid> rememberTemporaryGrid(ItemIdentityRegistry registry) throws Exception {
        IAEGrid grid = grid();
        registry.remember(grid, new Resource("iron", true));
        return new WeakReference<>(grid);
    }

    private static void awaitCollected(WeakReference<?> reference, Runnable maintenance) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
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
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                return null;
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
        public StableKey web$getKey() {
            encodings++;
            return StableKey.create(sink -> sink.putBytes(encodedName.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public IAEKey web$copyIdentity() {
            return new Resource(name, false, encodedName);
        }

        @Override
        public String web$getItemID() {
            return name;
        }

        @Override
        public String web$getDisplayName() {
            return name;
        }

        @Override
        public boolean web$isCraftable(IAEGrid grid) {
            return craftable;
        }

        @Override
        public boolean web$isSameType(IAEKey other) {
            return equals(other);
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
