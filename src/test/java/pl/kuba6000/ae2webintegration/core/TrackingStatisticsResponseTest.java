package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.ae2request.async.GetTracking;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetCPU;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.JSON_Stack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

class TrackingStatisticsResponseTest {

    private static final long GRID = 990_581L;

    private static final IStackList EMPTY_ITEMS = new IStackList() {

        public long web$getAmount(IAEKey key) {
            return 0;
        }

        public Iterable<IAEGenericStack> web$stacks() {
            return Collections.emptyList();
        }
    };

    @AfterEach
    void tearDown() {
        GridData.getOrCreate(GRID).trackingInfo.clearHistory();
        GridAccessSessions.clear();
        AE2JobTracker.clearActiveJobs();
    }

    @ParameterizedTest
    @CsvSource({ "0,0,0,0,0", "0,0,10,0,0", "1000,0,10,0,0", "1000,500,10,0.5,20" })
    void historySerializesFiniteStatisticsEvenWhenNoTimeWasMeasured(long elapsed, long spent, long crafted,
        double expectedShare, double expectedRate) {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(GRID);
        OutputSnapshotTest.Resource key = new OutputSnapshotTest.Resource();
        AE2JobTracker.JobTrackingInfo info = new AE2JobTracker.JobTrackingInfo(
            JSON_Stack.capture(grid, new OutputSnapshotTest.Stack(key, 10)));
        // Completed measurement records, including legitimate sub-millisecond intervals rounded to zero.
        info.timeStarted = 1000;
        info.timeDone = 1000 + elapsed;
        info.isDone = true;
        info.timeSpentOn.put(key, spent);
        info.craftedTotal.put(key, crafted);
        info.itemShare.put(key, new ArrayList<>(Collections.singletonList(Pair.of(1000L, 1000L + spent))));
        GridData.getOrCreate(GRID).trackingInfo.trackingInfos.put(1, info);
        GridAccessSessions
            .put(WebPrincipal.admin(), new GridAccess(-1, Collections.singleton(GRID), System.currentTimeMillis()));

        GetTracking request = new GetTracking();
        request.handle(TestGridFixtures.context(-1, "grid=" + GRID + "&id=1"));
        JsonObject response = JsonParser.parseString(request.getJSON())
            .getAsJsonObject();
        assertEquals(
            "OK",
            response.get("status")
                .getAsString());
        JsonObject item = response.getAsJsonObject("data")
            .getAsJsonArray("items")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            crafted,
            item.get("craftedTotal")
                .getAsLong());
        assertEquals(
            expectedShare,
            item.get("shareInCraftingTimeCombined")
                .getAsDouble(),
            0.000001);
        assertEquals(
            expectedRate,
            item.get("craftsPerSec")
                .getAsDouble(),
            0.000001);
        assertTrue(
            Double.isFinite(
                item.get("shareInCraftingTime")
                    .getAsDouble()));
        assertEquals(
            1,
            item.getAsJsonArray("timings")
                .size());
    }

    @ParameterizedTest
    @CsvSource({ "0,0,false,0", "0,10,false,0", "500,10,false,20", "500,10,true,20" })
    void activeCpuSerializesFiniteRatesAndHandlesClockMovingBeforeJobStart(long spent, long crafted,
        boolean clockMovedBack, double expectedRate) {
        OutputSnapshotTest.Resource key = new OutputSnapshotTest.Resource();
        OutputSnapshotTest.Stack output = new OutputSnapshotTest.Stack(key, 10);
        ICraftingCPUCluster cpu = (ICraftingCPUCluster) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { ICraftingCPUCluster.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "web$getFinalOutput":
                        return output;
                    case "web$getId":
                        return "ae2:0:1:2:3";
                    case "web$getName":
                        return "cpu";
                    case "web$isBusy":
                        return true;
                    case "web$getAvailableStorage":
                        return 64L;
                    case "web$getAllItems":
                        return null;
                    default:
                        throw new AssertionError("Unexpected CPU call: " + method.getName());
                }
            });
        IAECraftingGrid crafting = (IAECraftingGrid) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { IAECraftingGrid.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("web$getCPUs")) return Collections.singleton(cpu);
                throw new AssertionError("Unexpected crafting service call: " + method.getName());
            });
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(
            GRID,
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
                return EMPTY_ITEMS;
            }
        };
        GridData.getOrCreate(GRID).isTracked = true;
        AE2JobTracker.addJob(cpu, crafting, grid, false);
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        info.timeStarted = System.currentTimeMillis() + (clockMovedBack ? 60_000 : -60_000);
        // One already measured stage of a still-active job; its duration may legitimately be zero.
        info.timeSpentOn.put(key, spent);
        info.craftedTotal.put(key, crafted);
        IAE previous = AE2Controller.AE2Interface;
        AE2Controller.AE2Interface = ae;
        try {
            GetCPU request = new GetCPU();
            assertTrue(request.init(TestGridFixtures.context(-1, "grid=" + GRID + "&cpu=ae2:0:1:2:3")));
            request.runOnServerThread(ae);
            JsonObject response = JsonParser.parseString(request.getJSON())
                .getAsJsonObject();
            assertEquals(
                "OK",
                response.get("status")
                    .getAsString());
            JsonObject item = response.getAsJsonObject("data")
                .getAsJsonArray("items")
                .get(0)
                .getAsJsonObject();
            assertEquals(
                crafted,
                item.get("craftedTotal")
                    .getAsLong());
            assertEquals(
                expectedRate,
                item.get("craftsPerSec")
                    .getAsDouble(),
                0.000001);
            double share = item.get("shareInCraftingTimeCombined")
                .getAsDouble();
            assertTrue(Double.isFinite(share) && share >= 0 && share <= 1);
            if (clockMovedBack) assertEquals(0d, share);
        } finally {
            AE2Controller.AE2Interface = previous;
        }
    }
}
