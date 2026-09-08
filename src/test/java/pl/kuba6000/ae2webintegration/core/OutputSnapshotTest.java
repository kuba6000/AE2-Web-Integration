package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetCPU;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetCPUList;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.JSON_Stack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

class OutputSnapshotTest {

    @BeforeEach
    void clearRegistry() {
        AE2Controller.itemIdentities.clear();
    }

    @Test
    void capturedOutputSerializesAfterNativeAccessIsUnavailable() {
        Resource key = new Resource();
        Stack stack = new Stack(key, 5);
        IAEGrid grid = TestGridFixtures.grid(990125);
        JSON_Stack snapshot = JSON_Stack.capture(grid, stack);
        key.unavailable = true;
        stack.unavailable = true;
        JsonObject json = JsonParser.parseString(
            GSONUtils.GSON_BUILDER.create()
                .toJson(snapshot))
            .getAsJsonObject();
        assertEquals(
            "example:resource:7",
            json.get("itemid")
                .getAsString());
        assertEquals(
            "Resource",
            json.get("itemname")
                .getAsString());
        assertEquals(
            5,
            json.get("quantity")
                .getAsLong());
        assertFalse(json.has("hashcode"));
        assertNotNull(
            AE2Controller.itemIdentities.resolve(
                StableKey.parse(
                    json.get("itemKey")
                        .getAsString())));
        AE2Controller.itemIdentities.beginListing(grid)
            .commit();
    }

    @Test
    void failedIdentityPreservesDisplayWithOrdinaryNullableJsonFields() {
        Resource key = new Resource();
        key.brokenIdentity = true;
        JSON_Stack snapshot = assertDoesNotThrow(
            () -> JSON_Stack.capture(TestGridFixtures.grid(990125), new Stack(key, 9)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("output", snapshot);
        response.put("unrelated", null);
        JsonObject json = JsonParser.parseString(
            GSONUtils.GSON_BUILDER.create()
                .toJson(response))
            .getAsJsonObject();
        JsonObject output = json.getAsJsonObject("output");
        assertEquals(
            "Resource",
            output.get("itemname")
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
    void unsupportedNativeIdentityCannotEscapeSnapshotCapture() {
        Resource key = new Resource();
        key.unsupportedIdentity = true;
        JSON_Stack snapshot = assertDoesNotThrow(
            () -> JSON_Stack.capture(TestGridFixtures.grid(990125), new Stack(key, 4)));
        JsonObject json = JsonParser.parseString(
            GSONUtils.GSON_BUILDER.create()
                .toJson(snapshot))
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
        assertThrows(
            IllegalStateException.class,
            () -> JSON_Stack.capture(TestGridFixtures.grid(990125), new Stack(key, 6)));
    }

    @Test
    void cpuEndpointsIncludeCapturedResourceIdentity() {
        Stack output = new Stack(new Resource(), 12);
        ICraftingCPUCluster cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "web$getFinalOutput":
                        return output;
                    case "web$getId":
                        return StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
                    case "web$getName":
                        return "cpu";
                    case "web$isBusy":
                        return true;
                    case "web$getAvailableStorage":
                        return 64L;
                    case "web$getUsedStorage":
                        return 16L;
                    case "web$getCoProcessors":
                        return 1L;
                    case "web$getAllItems":
                        return null;
                    default:
                        throw new AssertionError("Unexpected CPU operation: " + method.getName());
                }
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
            true,
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
                assertTrue(request.init(TestGridFixtures.context(-1, "grid=990124&cpu=AAAAAAAAAAAAAAAAAAAAAA")));
                request.runOnServerThread(ae);
                JsonObject data = JsonParser.parseString(request.getJSON())
                    .getAsJsonObject()
                    .getAsJsonObject("data");
                if (data.has("AAAAAAAAAAAAAAAAAAAAAA")) data = data.getAsJsonObject("AAAAAAAAAAAAAAAAAAAAAA");
                JsonObject snapshot = data.getAsJsonObject("finalOutput");
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
        GridData.getOrCreate(gridId).isTracked = true;
        AtomicReference<Stack> output = new AtomicReference<>(new Stack(new Resource(), 5));
        ICraftingCPUCluster cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("web$getFinalOutput")) return output.get();
                throw new AssertionError("Unexpected native CPU call");
            });
        AE2JobTracker.addJob(cpu, null, TestGridFixtures.grid(gridId), false);
        Object initial = AE2JobTracker.findActiveJob(cpu).finalOutput;
        output.get().unavailable = true;
        output.get().key.unavailable = true;
        output.set(new Stack(new Resource(), 9));
        AE2JobTracker.addJob(cpu, null, TestGridFixtures.grid(gridId), true);
        Object merged = AE2JobTracker.findActiveJob(cpu).finalOutput;
        output.get().unavailable = true;
        output.get().key.unavailable = true;
        JsonObject before = JsonParser.parseString(
            GSONUtils.GSON_BUILDER.create()
                .toJson(initial))
            .getAsJsonObject();
        JsonObject after = JsonParser.parseString(
            GSONUtils.GSON_BUILDER.create()
                .toJson(merged))
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

        boolean unavailable;
        boolean brokenIdentity;
        boolean unsupportedIdentity;
        boolean brokenName;

        private void available() {
            if (unavailable) throw new AssertionError("Native access after capture");
        }

        public String web$getItemID() {
            available();
            return "example:resource:7";
        }

        public String web$getDisplayName() {
            available();
            if (brokenName) throw new IllegalStateException("Broken native name");
            return "Resource";
        }

        public boolean web$isCraftable(IAEGrid grid) {
            throw new AssertionError("Snapshot must not query recipes");
        }

        public boolean web$isSameType(IAEKey key) {
            return equals(key);
        }

        public StableKey web$getStableKey() {
            available();
            if (brokenIdentity) throw new IllegalStateException("Broken native codec");
            if (unsupportedIdentity) throw new UnsupportedOperationException("Unsupported native identity");
            return StableKey.create(sink -> sink.putBytes("resource".getBytes(StandardCharsets.UTF_8)));
        }

        public IAEKey web$copyIdentity() {
            available();
            Resource copy = new Resource();
            copy.brokenIdentity = brokenIdentity;
            copy.unsupportedIdentity = unsupportedIdentity;
            copy.brokenName = brokenName;
            return copy;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Resource;
        }

        @Override
        public int hashCode() {
            return 17;
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

        public IAEKey web$what() {
            available();
            return key;
        }

        public long web$amount() {
            available();
            return quantity;
        }

        public IAEGenericStack web$copy() {
            throw new AssertionError("Snapshot must not retain native stack");
        }

        @Override
        public int hashCode() {
            available();
            return 41;
        }
    }
}
