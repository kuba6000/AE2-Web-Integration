package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.*;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;
import pl.kuba6000.ae2webintegration.core.interfaces.*;
import pl.kuba6000.ae2webintegration.core.interfaces.service.*;

class ItemIdentityRequestTest {

    @Test
    void completeListingsReplaceOwnershipWithoutDiscardingAnotherGridsSharedKey() throws Exception {
        Grid first = new Grid(910009, new Resource("iron", 5, true));
        Grid second = new Grid(910010, new Resource("iron", 8, true) {

            @Override
            public StableItemKey web$getStableKey() {
                throw new AssertionError("Warm shared identity must not encode native data again");
            }
        });
        String key = run(new GetItems(), first, "").getAsJsonArray("data")
            .get(0)
            .getAsJsonObject()
            .get("itemKey")
            .getAsString();
        assertEquals(
            key,
            run(new GetItems(), second, "").getAsJsonArray("data")
                .get(0)
                .getAsJsonObject()
                .get("itemKey")
                .getAsString());
        first.rows.clear();
        assertEquals(
            "OK",
            run(new GetItems(), first, "").get("status")
                .getAsString());
        System.gc();
        assertEquals(
            "OK",
            run(new Order(), second, "&itemKey=" + key + "&quantity=1").get("status")
                .getAsString());
        second.rows.clear();
        assertEquals(
            "OK",
            run(new GetItems(), second, "").get("status")
                .getAsString());
        long deadline = System.nanoTime() + 5_000_000_000L;
        String status;
        do {
            System.gc();
            Thread.sleep(10);
            status = run(new Order(), second, "&itemKey=" + key + "&quantity=1").get("status")
                .getAsString();
        } while (status.equals("ITEM_NOT_FOUND") && System.nanoTime() < deadline);
        assertEquals("ITEM_IDENTITY_UNKNOWN", status);
        assertEquals(1, second.jobs);
    }

    @Test
    void failedListingTraversalPreservesPreviouslyCompletedOwnership() {
        Grid grid = new Grid(910011, new Resource("iron", 5, true));
        String key = run(new GetItems(), grid, "").getAsJsonArray("data")
            .get(0)
            .getAsJsonObject()
            .get("itemKey")
            .getAsString();
        grid.rows.clear();
        grid.rows.add(new Resource("gold", 2, true));
        grid.rows.add(new Resource("broken", 1, false) {

            @Override
            public String web$getDisplayName() {
                throw new IllegalStateException("Native traversal failed");
            }
        });
        assertThrows(IllegalStateException.class, () -> run(new GetItems(), grid, ""));
        System.gc();
        grid.rows.clear();
        grid.rows.add(new Resource("iron", 5, true));
        assertEquals(
            "OK",
            run(new Order(), grid, "&itemKey=" + key + "&quantity=1").get("status")
                .getAsString());
    }

    @Test
    void storedAndRecipeRowsMergeOnlyForPreciselyEqualResources() {
        Resource stored = new Resource("variantA", 5, true) {

            @Override
            public boolean web$isSameType(IAEKey other) {
                return true;
            }
        };
        Grid grid = new Grid(910008, stored);
        grid.recipes = new LinkedHashSet<>(
            Arrays.asList(new Resource("variantA", 0, true), new Resource("variantB", 0, true) {

                @Override
                public boolean web$isSameType(IAEKey other) {
                    return true;
                }
            }));
        com.google.gson.JsonArray rows = run(new GetItems(), grid, "").getAsJsonArray("data");
        assertEquals(2, rows.size());
        assertEquals(
            5,
            rows.get(0)
                .getAsJsonObject()
                .get("quantity")
                .getAsLong());
        assertEquals(
            "variantB",
            rows.get(1)
                .getAsJsonObject()
                .get("itemid")
                .getAsString());
    }

    @Test
    void orderingRequiresTheRawItemKeyAndRejectsFormerInputs() {
        Grid grid = new Grid(910007, new Resource("iron", 1, true));
        String key = run(new GetItems(), grid, "").getAsJsonArray("data")
            .get(0)
            .getAsJsonObject()
            .get("itemKey")
            .getAsString();
        assertEquals(
            "NO_PARAM",
            run(new Order(), grid, "&item=2112&quantity=1").get("status")
                .getAsString());
        assertEquals(
            "BAD_PARAM",
            run(new Order(), grid, "&itemKey=2112&quantity=1").get("status")
                .getAsString());
        assertEquals(
            "BAD_PARAM",
            run(new Order(), grid, "&itemKey=ik1:" + key + "&quantity=1").get("status")
                .getAsString());
        assertEquals(0, grid.jobs);
        assertEquals(
            "OK",
            run(new Order(), grid, "&itemKey=" + key + "&quantity=1").get("status")
                .getAsString());
    }

    @BeforeEach
    void clear() {
        AE2Controller.itemIdentities.clear();
        GridAccessSessions.clear();
    }

    @Test
    void identityIsIndependentOfCraftabilityAndUnavailableRowsKeepTheirDisplay() {
        Grid grid = new Grid(910003, new Resource("iron", 20, false), new Resource("unsupported", 3, true) {

            @Override
            public StableItemKey web$getStableKey() {
                throw new UnsupportedOperationException();
            }

            @Override
            public IAEKey web$copyIdentity() {
                return this;
            }
        });
        com.google.gson.JsonArray rows = run(new GetItems(), grid, "").getAsJsonArray("data");
        JsonObject normal = rows.get(0)
            .getAsJsonObject();
        assertTrue(normal.has("itemKey"));
        assertTrue(
            normal.get("identityStatus")
                .isJsonNull());
        assertFalse(normal.has("hashcode"));
        assertFalse(
            normal.get("craftable")
                .getAsBoolean());
        JsonObject unsupported = rows.get(1)
            .getAsJsonObject();
        assertTrue(
            unsupported.get("itemKey")
                .isJsonNull());
        assertFalse(unsupported.has("hashcode"));
        assertEquals(
            "UNSUPPORTED",
            unsupported.get("identityStatus")
                .getAsString());
        assertEquals(
            3,
            unsupported.get("quantity")
                .getAsLong());
        assertEquals(
            "unsupported",
            unsupported.get("itemname")
                .getAsString());
    }

    @Test
    void ordinaryHashCollisionsDoNotMergeStableKeys() {
        Grid grid = new Grid(910004, new Resource("Aa", 1, true), new Resource("BB", 1, true));
        com.google.gson.JsonArray rows = run(new GetItems(), grid, "").getAsJsonArray("data");
        String first = rows.get(0)
            .getAsJsonObject()
            .get("itemKey")
            .getAsString();
        String second = rows.get(1)
            .getAsJsonObject()
            .get("itemKey")
            .getAsString();
        assertNotEquals(first, second);
        assertEquals(
            "OK",
            run(new Order(), grid, "&itemKey=" + first + "&quantity=1").get("status")
                .getAsString());
        assertEquals("Aa", grid.ordered.web$getItemID());
    }

    @Test
    void malformedAndUnknownIdsDoNotStartJobs() {
        Grid grid = new Grid(910005, new Resource("iron", 1, true));
        assertEquals(
            "BAD_PARAM",
            run(new Order(), grid, "&itemKey=broken&quantity=1").get("status")
                .getAsString());
        assertEquals(
            "ITEM_IDENTITY_UNKNOWN",
            run(new Order(), grid, "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=1").get("status")
                .getAsString());
        assertEquals(0, grid.jobs);
    }

    @Test
    void authorizationHappensBeforeIdentityResolution() {
        Grid grid = new Grid(910006, new Resource("iron", 1, true));
        Order request = new Order();
        assertTrue(
            request
                .init(TestGridFixtures.context(42, "grid=" + grid.id + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=1")));
        request.runOnServerThread(TestGridFixtures.ae(grid));
        assertEquals(
            "NO_PERMISSIONS",
            JsonParser.parseString(request.getJSON())
                .getAsJsonObject()
                .get("status")
                .getAsString());
        assertEquals(0, grid.jobs);
    }

    @Test
    void listIdentitySurvivesAnotherGridAndOrderUsesCurrentCraftability() {
        Resource iron = new Resource("iron", 0, true);
        Grid first = new Grid(910001, iron);
        Grid second = new Grid(910002, new Resource("gold", 64, false));
        JsonObject row = run(new GetItems(), first, "").getAsJsonArray("data")
            .get(0)
            .getAsJsonObject();
        String key = row.get("itemKey")
            .getAsString();
        assertEquals(
            0,
            row.get("quantity")
                .getAsLong());
        assertTrue(
            row.get("craftable")
                .getAsBoolean());
        run(new GetItems(), second, "");
        first.currentCraftable = false;
        assertEquals(
            "ITEM_NOT_FOUND",
            run(new Order(), first, "&itemKey=" + key + "&quantity=2").get("status")
                .getAsString());
        assertEquals(0, first.jobs);
        first.currentCraftable = true;
        assertEquals(
            "OK",
            run(new Order(), first, "&itemKey=" + key + "&quantity=2147483648").get("status")
                .getAsString());
        assertEquals(1, first.jobs);
        assertEquals(2147483648L, first.orderedAmount);
        assertEquals("iron", first.ordered.web$getItemID());
    }

    private JsonObject run(ISyncedRequest request, Grid grid, String params) {
        IAE ae = new TestGridFixtures.TestAE(grid) {

            @Override
            public IAEGenericStack web$stackOf(IAEKey key, long amount) {
                return new IAEGenericStack() {

                    public IAEKey web$what() {
                        return key;
                    }

                    public long web$amount() {
                        return amount;
                    }

                    public IAEGenericStack web$copy() {
                        return this;
                    }
                };
            }
        };
        AE2Controller.AE2Interface = ae;
        if (request.init(TestGridFixtures.context(-1, "grid=" + grid.id + params))) request.runOnServerThread(ae);
        return JsonParser.parseString(request.getJSON())
            .getAsJsonObject();
    }

    static class Resource implements IAEKey, IAEGenericStack {

        final String id;
        final long quantity;
        final boolean craftable;

        Resource(String id, long quantity, boolean craftable) {
            this.id = id;
            this.quantity = quantity;
            this.craftable = craftable;
        }

        public StableItemKey web$getStableKey() {
            return StableItemKey.create(sink -> sink.putBytes(id.getBytes(StandardCharsets.UTF_8)));
        }

        public IAEKey web$copyIdentity() {
            return new Resource(id, 0, false);
        }

        public String web$getItemID() {
            return id;
        }

        public String web$getDisplayName() {
            return id;
        }

        public boolean web$isCraftable(IAEGrid grid) {
            return craftable;
        }

        public boolean web$isSameType(IAEKey key) {
            return equals(key);
        }

        public IAEKey web$what() {
            return this;
        }

        public long web$amount() {
            return quantity;
        }

        public IAEGenericStack web$copy() {
            return new Resource(id, quantity, craftable);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Resource && id.equals(((Resource) other).id);
        }
    }

    static final class Grid extends TestGridFixtures.TestGrid implements IAECraftingGrid, IAEStorageGrid {

        final long id;
        final List<IAEGenericStack> rows;
        Set<IAEKey> recipes = Collections.emptySet();
        boolean currentCraftable = true;
        int jobs;
        long orderedAmount;
        IAEKey ordered;

        Grid(long id, Resource... rows) {
            super(id, true, false, AEControllerState.CONTROLLER_ONLINE);
            this.id = id;
            this.rows = new ArrayList<>(Arrays.asList(rows));
        }

        public IAECraftingGrid web$getCraftingGrid() {
            return this;
        }

        public IAEStorageGrid web$getStorageGrid() {
            return this;
        }

        public boolean web$isCurrentlyCraftable(IAEKey key) {
            return currentCraftable && rows.stream()
                .anyMatch(
                    row -> row.web$what()
                        .equals(key));
        }

        public int web$getCPUCount() {
            return 1;
        }

        public Set<ICraftingCPUCluster> web$getCPUs() {
            return Collections.singleton(
                (ICraftingCPUCluster) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { ICraftingCPUCluster.class },
                    (proxy, method, args) -> {
                        if (method.getName()
                            .equals("web$isBusy")) return false;
                        throw new AssertionError("Unexpected CPU operation: " + method.getName());
                    }));
        }

        public Future<IAECraftingJob> web$beginCraftingJob(IAEGrid grid, IAEKey key, long amount) {
            jobs++;
            ordered = key;
            orderedAmount = amount;
            return new CompletableFuture<>();
        }

        public String web$submitJob(IAECraftingJob job, ICraftingCPUCluster target, boolean power, IAEGrid grid) {
            throw new AssertionError();
        }

        public ICraftingMediumTracker web$getCraftingProviders() {
            throw new AssertionError();
        }

        public Set<IAEKey> web$getCraftables(Function<IAEKey, Boolean> filter) {
            return recipes;
        }

        public IAEMeInventoryItem web$getInventory() {
            throw new AssertionError();
        }

        public IStackList web$getStorageList() {
            return new IStackList() {

                public long web$getAmount(IAEKey key) {
                    return rows.stream()
                        .filter(
                            row -> row.web$what()
                                .equals(key))
                        .mapToLong(IAEGenericStack::web$amount)
                        .sum();
                }

                public Iterable<IAEGenericStack> web$stacks() {
                    return rows;
                }
            };
        }
    }
}
