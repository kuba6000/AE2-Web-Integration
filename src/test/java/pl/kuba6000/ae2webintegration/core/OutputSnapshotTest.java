package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceStack;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.GetCPU;
import pl.kuba6000.ae2webintegration.core.http.endpoint.cpu.GetCPUList;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

@SuppressWarnings({ "UnstableApiUsage", "PMD.AvoidMagicNumbers" })
class OutputSnapshotTest extends GridTestScope {

    @BeforeEach
    void clearRegistry() {
        AE2Controller.itemIdentities.clear();
    }

    @Test
    void detachedHistoryDoesNotRetainNativeIdentitiesThroughASurvivingGrid() throws Exception {
        IAEGrid grid = TestGridFixtures.grid(990125);
        for (int job = 0; job < 12; job++) {
            CpuFixtures.Resource resource = new CpuFixtures.Resource("completed-" + job);
            ResourceSnapshot snapshot = ResourceSnapshot.capture(resource, 5);
            WeakReference<IAEKey> identity = new WeakReference<>(AE2Controller.itemIdentities.resolve(resource.key()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (identity.get() != null && System.nanoTime() < deadline) {
                System.gc();
                AE2Controller.itemIdentities.cleanUp();
                Thread.sleep(10);
            }
            assertNull(identity.get(), "Detached history must not retain a native identity under its grid");
            assertNull(AE2Controller.itemIdentities.resolve(resource.key()));
            assertEquals(
                resource.key(),
                snapshot.description()
                    .itemKey());
            assertEquals(
                "Display completed-" + job,
                snapshot.description()
                    .displayName());
            assertEquals(5, snapshot.quantity());
        }
        assertNotNull(TestGridFixtures.resolvedKey(grid), "The native grid remains usable after history capture");
    }

    @Test
    void amountsShareDetachedDescriptionAcrossOwnersAndSurviveRegistryRetirement() {
        Resource resource = new Resource();
        IAEGrid firstGrid = TestGridFixtures.grid(990125);
        IAEGrid secondGrid = TestGridFixtures.grid(990126);
        AE2Controller.itemIdentities.remember(firstGrid, resource);
        AE2Controller.itemIdentities.remember(secondGrid, resource);
        ResourceSnapshot first = ResourceSnapshot.capture(resource, 5);
        ResourceSnapshot second = ResourceSnapshot.capture(resource, 17);
        assertSame(first.description(), second.description());
        assertEquals(5, first.quantity());
        assertEquals(17, second.quantity());
        AE2Controller.itemIdentities.clear();
        resource.unavailable = true;
        ResourceStack response = new ResourceStack(second, null);
        assertEquals("Resource", response.displayName);
        assertEquals(17, response.quantity);
    }

    @Test
    void capturedOutputSerializesAfterNativeAccessIsUnavailable() {
        Resource key = new Resource();
        Stack stack = new Stack(key, 5);
        ResourceSnapshot snapshot = ResourceSnapshot.capture(stack);
        key.unavailable = true;
        stack.unavailable = true;
        JsonObject json = new JsonParser().parse(
            GSONUtils.GSON_BUILDER.create()
                .toJson(new ResourceStack(snapshot, null)))
            .getAsJsonObject();
        assertEquals(
            "example",
            json.get("registryNamespace")
                .getAsString());
        assertEquals(
            "Resource",
            json.get("displayName")
                .getAsString());
        assertEquals(
            5,
            json.get("quantity")
                .getAsLong());
        assertEquals(
            "resource",
            json.get("registryPath")
                .getAsString());
        assertEquals(
            7,
            json.get("damage")
                .getAsInt());
        assertEquals(
            2,
            json.get("componentCount")
                .getAsInt());
        assertFalse(json.has("itemId"));
        assertFalse(json.has("itemName"));
        assertFalse(json.has("hashcode"));
        assertFalse(json.has("itemid"));
        assertFalse(json.has("itemname"));
        assertFalse(json.has("baseKey"));
        assertNotNull(
            StableKey.parse(
                json.get("itemKey")
                    .getAsString()));
    }

    @Test
    void outputIconsBelongToIndependentResponsePageTables(@TempDir Path directory) throws Exception {
        Resource key = new Resource();
        ResourceSnapshot snapshot = ResourceSnapshot.capture(new Stack(key, 5));
        assertNotNull(snapshot);
        assertNotNull(
            snapshot.description()
                .itemKey());
        StableKey otherKey = TestGridFixtures.key(8831);
        IconPack.Metadata metadata = new IconPack.Metadata(
            "1.7.10",
            "forge",
            "test",
            "test",
            "today",
            Collections.emptyMap(),
            Collections.emptyList());
        Path archive;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata, 64)) {
            int[] pixels = new int[4096];
            Arrays.fill(pixels, 0xff112233);
            writer.add(otherKey, pixels);
            Arrays.fill(pixels, 0xff445566);
            writer.add(
                snapshot.description()
                    .itemKey(),
                pixels);
            archive = writer.finish();
        }
        key.unavailable = true;
        try (IconPack pack = IconPack.open(archive, "1.7.10", "forge", "test")) {
            IconMappings first = new IconMappings(pack);
            first.resolve(otherKey, (StableKey) null);
            ResourceStack firstOutput = new ResourceStack(snapshot, first);
            IconMappings second = new IconMappings(pack);
            ResourceStack secondOutput = new ResourceStack(snapshot, second);
            ResourceStack withoutIcons = new ResourceStack(snapshot, null);
            Gson gson = GSONUtils.GSON_BUILDER.create();
            JsonObject firstJson = new JsonParser().parse(gson.toJson(firstOutput))
                .getAsJsonObject();
            JsonObject secondJson = new JsonParser().parse(gson.toJson(secondOutput))
                .getAsJsonObject();
            assertEquals(
                1,
                firstJson.getAsJsonObject("icon")
                    .get("page")
                    .getAsInt());
            assertEquals(
                0,
                secondJson.getAsJsonObject("icon")
                    .get("page")
                    .getAsInt());
            assertEquals(
                first.pages.get(1)
                    .digest(),
                second.pages.get(0)
                    .digest());
            assertEquals(
                snapshot.description()
                    .itemKey()
                    .toString(),
                firstJson.get("itemKey")
                    .getAsString());
            assertTrue(
                new JsonParser().parse(gson.toJson(withoutIcons))
                    .getAsJsonObject()
                    .get("icon")
                    .isJsonNull());
            assertFalse(firstJson.has("baseKey"));
            assertEquals(
                5,
                firstJson.get("quantity")
                    .getAsInt());
        }
    }

    @Test
    void failedIdentityPreservesDisplayWithOrdinaryNullableJsonFields() {
        Resource key = new Resource();
        key.brokenIdentity = true;
        ResourceSnapshot snapshot = assertDoesNotThrow(() -> ResourceSnapshot.capture(new Stack(key, 9)));
        assertNotNull(snapshot);
        Map<String, ResourceStack> response = new LinkedHashMap<>();
        response.put("output", new ResourceStack(snapshot, null));
        response.put("unrelated", null);
        JsonObject json = new JsonParser().parse(
            GSONUtils.GSON_BUILDER.create()
                .toJson(response))
            .getAsJsonObject();
        JsonObject output = json.getAsJsonObject("output");
        assertEquals(
            "Resource",
            output.get("displayName")
                .getAsString());
        assertEquals(
            9,
            output.get("quantity")
                .getAsLong());
        assertTrue(
            output.get("itemKey")
                .isJsonNull());
        assertTrue(json.has("unrelated"));
        assertTrue(
            json.get("unrelated")
                .isJsonNull());
    }

    @Test
    void knownRequestIdentityIsPreservedWithoutReadingTheNativeCodecAgain() {
        Resource key = new Resource();
        StableKey requestedKey = key.web$getKey();
        key.brokenIdentity = true;
        ResourceSnapshot snapshot = ResourceSnapshot.capture(key, 37, requestedKey);
        key.unavailable = true;
        ResourceStack output = new ResourceStack(snapshot, null);
        assertEquals(requestedKey, output.itemKey);
        assertEquals(37, output.quantity);
        assertEquals("Resource", output.displayName);
    }

    @Test
    void unsupportedNativeIdentityCannotEscapeSnapshotCapture() {
        Resource key = new Resource();
        key.unsupportedIdentity = true;
        ResourceSnapshot snapshot = assertDoesNotThrow(() -> ResourceSnapshot.capture(new Stack(key, 4)));
        assertNotNull(snapshot);
        JsonObject json = new JsonParser().parse(
            GSONUtils.GSON_BUILDER.create()
                .toJson(new ResourceStack(snapshot, null)))
            .getAsJsonObject();
        assertEquals(
            4,
            json.get("quantity")
                .getAsLong());
        assertTrue(
            json.get("itemKey")
                .isJsonNull());
    }

    @Test
    void brokenDisplayCallbackFailsSnapshotCapture() {
        Resource key = new Resource();
        key.brokenName = true;
        assertThrows(IllegalStateException.class, () -> ResourceSnapshot.capture(new Stack(key, 6)));
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void cpuEndpointsIncludeCapturedResourceIdentity(ResourceType resourceType) {
        Resource resource = new Resource();
        resource.resourceType = resourceType;
        Stack output = new Stack(resource, 12);
        ICraftingCPUCluster cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "web$getFinalOutput" -> output;
                case "web$getKey" -> StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
                case "web$getName" -> "cpu";
                case "web$isBusy" -> true;
                case "web$getSelectionMode" -> null;
                case "web$acceptsPlayerJobs" -> false;
                case "web$getAvailableStorage" -> 64L;
                case "web$getUsedStorage" -> 16L;
                case "web$getCoProcessors" -> 1L;
                case "web$getItemLists" -> null;
                default -> throw new AssertionError("Unexpected CPU operation: " + method.getName());
            });
        IAECraftingGrid crafting = (IAECraftingGrid) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { IAECraftingGrid.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("web$getCPUs")) return Collections.singleton(cpu);
                throw new AssertionError("Unexpected crafting service operation");
            });
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(
            990124,
            false,
            AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public IAECraftingGrid web$getCraftingGrid() {
                return crafting;
            }
        };
        TestGridFixtures.TestAE ae = new TestGridFixtures.TestAE(grid) {

            @Override
            public IStackList web$createStackList() {
                return new IStackList() {

                    public long web$getAmount(IAEKey key) {
                        return 0;
                    }

                    public Iterable<IAEGenericStack> web$stacks() {
                        return Collections.emptyList();
                    }
                };
            }
        };
        IAE previous = AE2Controller.AE2Interface;
        AE2Controller.AE2Interface = ae;
        try {
            for (ISyncedRequest request : new ISyncedRequest[] { new GetCPUList(), new GetCPU() }) {
                assertTrue(
                    request.init(
                        TestGridFixtures.context(
                            -1,
                            "grid=" + TestGridFixtures.resolvedKey(grid) + "&cpu=AAAAAAAAAAAAAAAAAAAAAA")));
                request.runOnServerThread(ae);
                JsonObject data = new JsonParser().parse(request.getJSON())
                    .getAsJsonObject()
                    .getAsJsonObject("data");
                if (data.has("AAAAAAAAAAAAAAAAAAAAAA")) data = data.getAsJsonObject("AAAAAAAAAAAAAAAAAAAAAA");
                JsonObject snapshot = data.getAsJsonObject("finalOutput");
                assertEquals(
                    resourceType.name(),
                    snapshot.get("resourceType")
                        .getAsString());
                assertFalse(snapshot.has("craftable"));
                assertEquals(
                    12,
                    snapshot.get("quantity")
                        .getAsLong());
                assertTrue(snapshot.has("itemKey"));
            }
        } finally {
            AE2Controller.AE2Interface = previous;
        }
    }

    @Test
    void trackingMergePublishesNewCapturedAmountWithoutRetainingNativeStack() {
        long gridId = 990123;
        AE2JobTracker.clearActiveJobs();
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(gridId);
        TestGridFixtures.track(grid);
        AtomicReference<Stack> output = new AtomicReference<>(new Stack(new Resource(), 5));
        ICraftingCPUCluster cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("web$getFinalOutput")) return output.get();
                throw new AssertionError("Unexpected native CPU call");
            });
        AE2JobTracker.addJob(cpu, grid, false, null);
        ResourceSnapshot initial = AE2JobTracker.findActiveJob(cpu).finalOutput;
        output.get().unavailable = true;
        output.get().key.unavailable = true;
        output.set(new Stack(new Resource(), 9));
        AE2JobTracker.addJob(cpu, grid, true, null);
        ResourceSnapshot merged = AE2JobTracker.findActiveJob(cpu).finalOutput;
        output.get().unavailable = true;
        output.get().key.unavailable = true;
        JsonObject before = new JsonParser().parse(
            GSONUtils.GSON_BUILDER.create()
                .toJson(new ResourceStack(initial, null)))
            .getAsJsonObject();
        JsonObject after = new JsonParser().parse(
            GSONUtils.GSON_BUILDER.create()
                .toJson(new ResourceStack(merged, null)))
            .getAsJsonObject();
        assertEquals(
            5,
            before.get("quantity")
                .getAsLong());
        assertEquals(
            9,
            after.get("quantity")
                .getAsLong());
        assertEquals(before.get("itemKey"), after.get("itemKey"));
    }

    static final class Resource implements IAEKey {

        ResourceType resourceType = ResourceType.ITEM;
        boolean unavailable;
        boolean brokenIdentity;
        boolean unsupportedIdentity;
        boolean brokenName;

        private void available() {
            if (unavailable) throw new AssertionError("Native access after capture");
        }

        public @NotNull ResourceType web$getResourceType() {
            available();
            return resourceType;
        }

        public @NotNull String web$getRegistryNamespace() {
            available();
            return "example";
        }

        public @NotNull String web$getRegistryPath() {
            available();
            return "resource";
        }

        public int web$getComponentCount() {
            available();
            return 2;
        }

        public int web$getDamage() {
            available();
            return 7;
        }

        public @NotNull String web$getDisplayName() {
            available();
            if (brokenName) throw new IllegalStateException("Broken native name");
            return "Resource";
        }

        public boolean web$isCraftable(IAEGrid grid) {
            throw new AssertionError("Snapshot must not query recipes");
        }

        public @NotNull StableKey web$getKey() {
            available();
            if (brokenIdentity) throw new IllegalStateException("Broken native codec");
            if (unsupportedIdentity) throw new UnsupportedOperationException("Unsupported native identity");
            return StableKey
                .create(sink -> sink.putBytes(("resource:" + resourceType.name()).getBytes(StandardCharsets.UTF_8)));
        }

        public @NotNull IAEKey web$copyIdentity() {
            available();
            Resource copy = new Resource();
            copy.resourceType = resourceType;
            copy.brokenIdentity = brokenIdentity;
            copy.unsupportedIdentity = unsupportedIdentity;
            copy.brokenName = brokenName;
            return copy;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Resource other && resourceType == other.resourceType;
        }

        @Override
        public int hashCode() {
            return resourceType.hashCode();
        }
    }

    static final class Stack implements IAEGenericStack {

        final Resource key;
        long quantity;
        boolean unavailable;

        Stack(Resource key, long quantity) {
            this.key = key;
            this.quantity = quantity;
        }

        private void available() {
            if (unavailable) throw new AssertionError("Native stack access after capture");
        }

        public @NotNull IAEKey web$what() {
            available();
            return key;
        }

        public long web$amount() {
            available();
            return quantity;
        }

        @Override
        public int hashCode() {
            available();
            return 41;
        }
    }
}
