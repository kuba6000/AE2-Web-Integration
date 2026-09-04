package pl.kuba6000.ae2webintegration.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

class ItemIdentityRegistryTest {

    @Test
    void warmEquivalentResourcesReuseIdentityEncodingAndClearDropsConflictHistory() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry(10, 100_000, 1000, () -> 0);
        Resource first = new Resource("iron", true);
        StableItemKey key = registry.remember(first);
        Resource nextPoll = new Resource("iron", false);
        assertEquals(key, registry.remember(nextPoll));
        assertEquals(1, first.encodings);
        assertEquals(0, nextPoll.encodings);
        registry.rejectLegacy(42);
        registry.rememberLegacy(42, key);
        assertThrows(ItemIdentityRegistry.Ambiguous.class, () -> registry.resolveLegacy(42));
        registry.clear();
        assertNull(registry.resolve(key));
        assertEquals(key, registry.remember(nextPoll));
        assertEquals(1, nextPoll.encodings);
    }

    @Test
    void aCopyWithTheWrongPreciseIdentityIsNeverPublished() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry(10, 100_000, 1000, () -> 0);
        Resource broken = new Resource("first", false, "same bytes") {

            @Override
            public IAEKey web$copyIdentity() {
                return new Resource("different", false, "same bytes");
            }
        };
        assertThrows(java.io.IOException.class, () -> registry.remember(broken));
        assertNull(registry.resolve(StableItemKey.fromIdentityBytes(broken.web$getIdentityBytes())));
    }

    @Test
    void legacyAliasAssignmentsStayStickyWhenPayloadExpiresAndStopClearsEverything() throws Exception {
        AtomicLong now = new AtomicLong();
        ItemIdentityRegistry registry = new ItemIdentityRegistry(10, 100_000, 1000, now::get);
        StableItemKey first = registry.remember(new Resource("iron", true));
        registry.rememberLegacy(12, first);
        assertEquals(
            "iron",
            registry.resolveLegacy(12)
                .web$getItemID());
        now.set(2000);
        assertNull(registry.resolveLegacy(12));
        StableItemKey second = registry.remember(new Resource("gold", true));
        registry.rememberLegacy(12, second);
        assertThrows(ItemIdentityRegistry.Ambiguous.class, () -> registry.resolveLegacy(12));
        registry.clear();
        assertNull(registry.resolve(first));
        assertNull(registry.resolve(second));
        assertNull(registry.resolveLegacy(12));
        registry.rememberLegacy(12, registry.remember(new Resource("gold", true)));
        assertEquals(
            "gold",
            registry.resolveLegacy(12)
                .web$getItemID());
    }

    @Test
    void fullLegacyHistoryDisablesOldOrdersWhileStableKeysStillWork() throws Exception {
        ItemIdentityRegistry registry = new ItemIdentityRegistry(1, 100_000, 1000, () -> 0);
        StableItemKey key = registry.remember(new Resource("iron", false));
        registry.rememberLegacy(1, key);
        registry.rememberLegacy(2, key);
        assertThrows(IdentityLimitException.class, () -> registry.resolveLegacy(1));
        assertThrows(IdentityLimitException.class, () -> registry.resolveLegacy(2));
        assertNotNull(registry.resolve(key));
    }

    @Test
    void conflictingIdentityCannotRedefineAnIdEvenAfterExpiration() throws Exception {
        AtomicLong now = new AtomicLong();
        ItemIdentityRegistry registry = new ItemIdentityRegistry(10, 100_000, 1000, now::get);
        StableItemKey key = registry.remember(new Resource("first", false, "same bytes"));
        assertThrows(
            ItemIdentityRegistry.Ambiguous.class,
            () -> registry.remember(new Resource("second", false, "same bytes")));
        assertThrows(ItemIdentityRegistry.Ambiguous.class, () -> registry.resolve(key));
        now.set(2000);
        assertThrows(
            ItemIdentityRegistry.Ambiguous.class,
            () -> registry.remember(new Resource("first", false, "same bytes")));
    }

    @Test
    void capacityRejectsNewResourcesWithoutEvictingActiveOnesAndExpiryAllowsAdmission() throws Exception {
        AtomicLong now = new AtomicLong();
        ItemIdentityRegistry registry = new ItemIdentityRegistry(1, 100_000, 1000, now::get);
        StableItemKey iron = registry.remember(new Resource("iron", true));
        assertThrows(IdentityLimitException.class, () -> registry.remember(new Resource("gold", true)));
        now.set(999);
        assertNotNull(registry.resolve(iron));
        now.set(1500);
        assertThrows(IdentityLimitException.class, () -> registry.remember(new Resource("gold", true)));
        now.set(2000);
        assertNull(registry.resolve(iron));
        StableItemKey gold = registry.remember(new Resource("gold", false));
        assertEquals(
            "gold",
            registry.resolve(gold)
                .web$getItemID());
        ItemIdentityRegistry tooSmall = new ItemIdentityRegistry(10, 1, 1000, now::get);
        assertThrows(IdentityLimitException.class, () -> tooSmall.remember(new Resource("iron", false)));
    }

    @Test
    void rememberedIdentitySurvivesOtherListsAndDoesNotRetainCraftingFlags() throws Exception {
        AtomicLong now = new AtomicLong();
        ItemIdentityRegistry registry = new ItemIdentityRegistry(10, 100_000, 1000, now::get);
        Resource iron = new Resource("iron", true);
        StableItemKey key = registry.remember(iron);
        registry.remember(new Resource("gold", false));
        IAEKey resolved = registry.resolve(StableItemKey.parse(key.toString()));
        assertEquals("iron", resolved.web$getItemID());
        assertNotSame(iron, resolved);
        assertFalse(resolved.web$isCraftable(null));
        assertEquals(key, registry.remember(new Resource("iron", false)));
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
        public byte[] web$getIdentityBytes() {
            encodings++;
            return encodedName.getBytes(StandardCharsets.UTF_8);
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
