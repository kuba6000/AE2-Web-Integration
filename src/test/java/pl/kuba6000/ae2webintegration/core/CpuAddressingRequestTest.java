package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.CancelCPU;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetCPU;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.GetCPUList;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.Job;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

@SuppressWarnings({ "UnstableApiUsage", "PMD.AvoidMagicNumbers" })
class CpuAddressingRequestTest {

    private static final long GRID = 991234;

    @Test
    void lookupUsesTheFullStableKeyEvenWhenMapHashCodesCollide() {
        TestCpu first = new TestCpu("AAAAAAAAAAAAAAAAAAAAAA", "Same name", 64);
        TestCpu second = new TestCpu("AAAAAAAAAAEAAAAA____4Q", "Same name", 128);
        StableKey firstKey = StableKey.parse(first.id);
        StableKey secondKey = StableKey.parse(second.id);
        assertEquals(firstKey.hashCode(), secondKey.hashCode());
        TestGrid grid = new TestGrid(GRID, first, second);
        assertSame(
            first,
            GetCPUList.getCPUList(grid.web$getCraftingGrid())
                .get(firstKey));
        assertSame(
            second,
            GetCPUList.getCPUList(grid.web$getCraftingGrid())
                .get(secondKey));
        assertStatus("OK", submit(grid, "&cpu=" + second.id));
        assertSame(second, grid.submitted);
    }

    @AfterEach
    void clearPlans() {
        GridData.clearRuntimeState();
    }

    @Test
    void equallyNamedCpusAreListedIndividuallyByAddress() {
        TestCpu first = new TestCpu(
            StableKey.create(sink -> sink.putInt(1))
                .toString(),
            "Main CPU",
            64);
        TestCpu second = new TestCpu(
            StableKey.create(sink -> sink.putInt(2))
                .toString(),
            "Main CPU",
            128);
        JsonObject response = request(new GetCPUList(), new TestGrid(GRID, first, second), "");
        assertStatus("OK", response);
        JsonObject cpus = response.getAsJsonObject("data");
        assertEquals(2, cpus.size());
        assertEquals(
            "Main CPU",
            cpus.getAsJsonObject(first.id)
                .get("name")
                .getAsString());
        assertEquals(
            "Main CPU",
            cpus.getAsJsonObject(second.id)
                .get("name")
                .getAsString());
        assertEquals(
            64,
            cpus.getAsJsonObject(first.id)
                .get("availableStorage")
                .getAsLong());
        assertEquals(
            128,
            cpus.getAsJsonObject(second.id)
                .get("availableStorage")
                .getAsLong());
    }

    @Test
    void selectionSurvivesRenameReorderAndReconstructedObjects() {
        TestCpu first = new TestCpu(
            StableKey.create(sink -> sink.putInt(1))
                .toString(),
            "Main CPU",
            64);
        TestCpu second = new TestCpu(
            StableKey.create(sink -> sink.putInt(2))
                .toString(),
            "Main CPU",
            128);
        TestGrid grid = new TestGrid(GRID, first, second);
        assertEquals(
            64,
            request(new GetCPU(), grid, "&cpu=" + first.id).getAsJsonObject("data")
                .get("size")
                .getAsLong());
        first.name = "Renamed";
        grid.cpus = new LinkedHashSet<>(Arrays.asList(second, first));
        assertEquals(
            64,
            request(new GetCPU(), grid, "&cpu=" + first.id).getAsJsonObject("data")
                .get("size")
                .getAsLong());
        TestCpu restored = new TestCpu(
            StableKey.create(sink -> sink.putInt(1))
                .toString(),
            "Renamed",
            256);
        grid.cpus = new LinkedHashSet<>(Arrays.asList(second, restored));
        assertEquals(
            256,
            request(new GetCPU(), grid, "&cpu=" + first.id).getAsJsonObject("data")
                .get("size")
                .getAsLong());
    }

    @Test
    void cancellationTargetsOnlyTheSelectedAddress() {
        TestCpu first = new TestCpu(
            StableKey.create(sink -> sink.putInt(1))
                .toString(),
            "Main CPU",
            64);
        TestCpu second = new TestCpu(
            StableKey.create(sink -> sink.putInt(2))
                .toString(),
            "Main CPU",
            128);
        first.busy = second.busy = true;
        TestGrid grid = new TestGrid(GRID, first, second);
        assertStatus("OK", request(new CancelCPU(), grid, "&cpu=" + first.id));
        assertTrue(first.cancelled);
        assertFalse(second.cancelled);
        assertTrue(second.busy);
    }

    @Test
    void explicitSubmissionTargetsItsAddressAndAbsentSelectionUsesAutomaticChoice() {
        TestCpu first = new TestCpu(
            StableKey.create(sink -> sink.putInt(1))
                .toString(),
            "Main CPU",
            64);
        TestCpu second = new TestCpu(
            StableKey.create(sink -> sink.putInt(2))
                .toString(),
            "Main CPU",
            128);
        TestGrid grid = new TestGrid(GRID, first, second);
        assertStatus("OK", submit(grid, "&cpu=" + first.id));
        assertSame(first, grid.submitted);
        assertStatus("OK", submit(grid, ""));
        assertNull(grid.submitted);
        assertEquals(2, grid.submissions);
    }

    @Test
    void missingExplicitAddressAndOldDisplayNameNeverSelectAnotherCpu() {
        TestCpu cpu = new TestCpu(
            StableKey.create(sink -> sink.putInt(1))
                .toString(),
            "Main",
            64);
        TestGrid grid = new TestGrid(GRID, cpu);
        for (String invalid : new String[] { StableKey.create(sink -> sink.putInt(2))
            .toString(), "Main", "" }) {
            assertStatus("CPU_NOT_FOUND", request(new GetCPU(), grid, "&cpu=" + invalid));
            assertStatus("CPU_NOT_FOUND", request(new CancelCPU(), grid, "&cpu=" + invalid));
            assertStatus("CPU_NOT_FOUND", submit(grid, "&cpu=" + invalid));
        }
        grid.cpus.clear();
        assertStatus("CPU_NOT_FOUND", submit(grid, "&cpu=" + cpu.id));
        assertEquals(0, grid.submissions);
        assertFalse(cpu.cancelled);
    }

    @Test
    void addressFromAnotherGridDoesNotResolve() {
        TestCpu remote = new TestCpu(
            StableKey.create(sink -> sink.putInt(1))
                .toString(),
            "Main",
            64);
        TestGrid selected = new TestGrid(GRID);
        TestGrid other = new TestGrid(GRID + 1, remote);
        for (ISyncedRequest request : new ISyncedRequest[] { new GetCPU(), new CancelCPU(), new Job() }) {
            assertTrue(
                request.init(
                    TestGridFixtures.context(-1, "grid=" + GRID + "&cpu=" + remote.id + "&submit&id=" + addPlan())));
            request.handle(TestGridFixtures.ae(selected, other));
            assertStatus(
                "CPU_NOT_FOUND",
                JsonParser.parseString(request.getJSON())
                    .getAsJsonObject());
        }
        assertEquals(0, selected.submissions);
        assertEquals(0, other.submissions);
        assertFalse(remote.cancelled);
    }

    @Test
    void duplicateAddressesAreLoggedWithoutDroppingTheRestOfTheList() {
        TestCpu first = new TestCpu(
            StableKey.create(sink -> sink.putInt(1))
                .toString(),
            "First",
            64);
        TestCpu second = new TestCpu(first.id, "Second", 128);
        TestCpu third = new TestCpu(
            StableKey.create(sink -> sink.putInt(2))
                .toString(),
            "Third",
            256);
        TestGrid grid = new TestGrid(GRID, first, second, third);
        ArrayList<String> errors = new ArrayList<>();
        Logger logger = (Logger) LogManager.getLogger("ae2webintegration");
        AbstractAppender appender = new AbstractAppender("cpu-id-errors", null, null, false, Property.EMPTY_ARRAY) {

            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.ERROR) errors.add(
                    event.getMessage()
                        .getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            JsonObject response = request(new GetCPUList(), grid, "");
            assertStatus("OK", response);
            JsonObject cpus = response.getAsJsonObject("data");
            assertEquals(2, cpus.size());
            assertEquals(
                "Second",
                cpus.getAsJsonObject(first.id)
                    .get("name")
                    .getAsString());
            assertEquals(
                "Third",
                cpus.getAsJsonObject(third.id)
                    .get("name")
                    .getAsString());
            assertTrue(
                errors.stream()
                    .anyMatch(message -> message.contains(first.id)));
            assertStatus("OK", request(new GetCPU(), grid, "&cpu=" + third.id));
            first.busy = second.busy = true;
            assertStatus("OK", request(new CancelCPU(), grid, "&cpu=" + first.id));
            assertFalse(first.cancelled);
            assertTrue(second.cancelled);
            assertFalse(third.cancelled);
            assertStatus("OK", submit(grid, "&cpu=" + first.id));
            assertSame(second, grid.submitted);
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    private static int addPlan() {
        IAECraftingJob plan = (IAECraftingJob) Proxy.newProxyInstance(
            CpuAddressingRequestTest.class.getClassLoader(),
            new Class<?>[] { IAECraftingJob.class },
            (proxy, method, args) -> {
                throw new AssertionError("Unexpected plan operation");
            });
        return GridData.getOrCreate(GRID)
            .addJob(CompletableFuture.completedFuture(plan));
    }

    private static JsonObject submit(TestGrid grid, String selection) {
        return request(new Job(), grid, "&id=" + addPlan() + "&submit" + selection);
    }

    private static JsonObject request(ISyncedRequest request, TestGrid grid, String params) {
        if (request.init(TestGridFixtures.context(-1, "grid=" + GRID + params))) {
            request.handle(TestGridFixtures.ae(grid));
        }
        return JsonParser.parseString(request.getJSON())
            .getAsJsonObject();
    }

    private static void assertStatus(String expected, JsonObject response) {
        assertEquals(
            expected,
            response.get("status")
                .getAsString(),
            response.toString());
    }

    private static final class TestGrid extends TestGridFixtures.TestGrid {

        private Set<ICraftingCPUCluster> cpus;
        private ICraftingCPUCluster submitted;
        private int submissions;

        TestGrid(long key, TestCpu... cpus) {
            super(key, true, false, AEControllerState.CONTROLLER_ONLINE);
            this.cpus = new LinkedHashSet<>(Arrays.asList(cpus));
        }

        @Override
        public IAECraftingGrid web$getCraftingGrid() {
            return (IAECraftingGrid) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IAECraftingGrid.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "web$getCPUs":
                            return cpus;
                        case "web$submitJob":
                            submitted = (ICraftingCPUCluster) args[1];
                            submissions++;
                            return null;
                        default:
                            throw new AssertionError("Unexpected crafting operation: " + method.getName());
                    }
                });
        }
    }

    private static final class TestCpu implements ICraftingCPUCluster {

        final String id;
        final StableKey key;
        String name;
        final long storage;
        boolean busy;
        boolean cancelled;

        TestCpu(String id, String name, long storage) {
            this.id = id;
            this.key = StableKey.parse(id);
            this.name = name;
            this.storage = storage;
        }

        public @NotNull StableKey web$getKey() {
            return key;
        }

        public String web$getName() {
            return name;
        }

        public long web$getAvailableStorage() {
            return storage;
        }

        public long web$getUsedStorage() {
            return 0;
        }

        public long web$getCoProcessors() {
            return 0;
        }

        public boolean web$isBusy() {
            return busy;
        }

        public void web$cancel() {
            cancelled = true;
            busy = false;
        }

        public IAEGenericStack web$getFinalOutput() {
            return null;
        }

        public long web$getActiveItems(IAEKey key) {
            return 0;
        }

        public long web$getPendingItems(IAEKey key) {
            return 0;
        }

        public long web$getStorageItems(IAEKey key) {
            return 0;
        }

        public void web$getAllItems(IStackList list) {}

        public IStackList web$getWaitingFor() {
            throw new AssertionError("Unexpected waiting inventory");
        }
    }
}
