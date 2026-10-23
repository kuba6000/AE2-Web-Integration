package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.cpu.CpuObservation;
import pl.kuba6000.ae2webintegration.core.cpu.CpuReader;
import pl.kuba6000.ae2webintegration.core.http.dto.GetCPUClusterData;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.AEMixinCallbacks;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CpuReaderTest extends GridTestScope {

    private final CpuFixtures.Resource iron = new CpuFixtures.Resource("iron");
    private final CpuFixtures.Resource gear = new CpuFixtures.Resource("gear");
    private final CpuFixtures.Resource exotic = new CpuFixtures.Resource("exotic", false);
    private final CpuFixtures.Cpu cpu = new CpuFixtures.Cpu(1);
    private final CpuFixtures.Grid grid = new CpuFixtures.Grid(9101, cpu);
    private IAE previousAe;

    @BeforeEach
    void bindNative() {
        AE2Controller.itemIdentities.clear();
        previousAe = AE2Controller.AE2Interface;
        AE2Controller.AE2Interface = new CpuFixtures.Ae(grid);
        TestGridFixtures.resolvedKey(grid);
        cpu.busy = true;
        cpu.output = gear;
    }

    @AfterEach
    void unbindNative() {
        AE2Controller.AE2Interface = previousAe;
        AE2JobTracker.clearActiveJobs();
        AE2Controller.itemIdentities.clear();
    }

    @Test
    void requesterHeadersRemainDetachedAcrossMergesAndDisappearingCpuWork() {
        TestGridFixtures.track(grid);
        PlayerIdentity first = TestGridFixtures.playerIdentity(7);
        PlayerIdentity second = TestGridFixtures.playerIdentity(8);
        AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, false, true, first);
        CpuObservation initial = CpuReader.read(cpu, true);
        AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, true, true, second);
        AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, true, false, null);
        CpuObservation merged = CpuReader.read(cpu, cpu, Collections.emptyList());
        assertEquals(
            Collections.singletonList(CraftingRequester.player(first)),
            initial.header()
                .requesters());
        assertEquals(
            Arrays.asList(
                CraftingRequester.player(first),
                CraftingRequester.player(second),
                CraftingRequester.automation()),
            merged.header()
                .requesters());
        GetCPUClusterData body = new GetCPUClusterData(merged.header(), 1000, null);
        assertEquals(
            Arrays.asList(
                CraftingRequester.player(first),
                CraftingRequester.player(second),
                CraftingRequester.automation()),
            body.requesters);
        cpu.busy = false;
        assertTrue(
            CpuReader.read(cpu, true)
                .header()
                .requesters()
                .isEmpty());
    }

    @Test
    void incrementalRemovalReleasesOnlyMissingRowsAndKeepsTheCurrentOutput() throws Exception {
        CpuFixtures.Resource shared = new CpuFixtures.Resource("shared");
        cpu.set(iron, 1, 0, 0)
            .set(shared, 0, 1, 0);
        AE2Controller.itemIdentities.remember(grid, shared);
        CpuReader.read(cpu, true);
        WeakReference<IAEKey> removed = new WeakReference<>(AE2Controller.itemIdentities.resolve(iron.key()));
        assertNotNull(removed.get());
        cpu.work.clear();

        CpuObservation delta = CpuReader.read(cpu, cpu, Arrays.asList(iron, shared, gear));

        assertEquals(
            3,
            delta.missing()
                .size());
        CpuFixtures.awaitCollected(removed);
        assertNull(AE2Controller.itemIdentities.resolve(iron.key()));
        assertNotNull(AE2Controller.itemIdentities.resolve(shared.key()), "Another owner retains its action lookup");
        assertNotNull(AE2Controller.itemIdentities.resolve(gear.key()), "The output remains actionable without a row");
        assertEquals(
            gear.key(),
            header(delta).finalOutput()
                .description()
                .itemKey());
    }

    @Test
    void completionReleasesCpuOwnershipEvenWithoutALiveCoordinator() throws Exception {
        assertNull(CoreEngine.getCpuLive());
        cpu.set(iron, 1, 0, 0);
        CpuReader.read(cpu, true);
        WeakReference<IAEKey> row = new WeakReference<>(AE2Controller.itemIdentities.resolve(iron.key()));
        WeakReference<IAEKey> output = new WeakReference<>(AE2Controller.itemIdentities.resolve(gear.key()));
        assertNotNull(row.get());
        assertNotNull(output.get());

        AEMixinCallbacks.INSTANCE.jobCompleted(grid, cpu);

        CpuFixtures.awaitCollected(row);
        CpuFixtures.awaitCollected(output);
        assertTrue(cpu.busy, "Native completion hooks can run before the CPU clears its own job");
    }

    private CpuObservation.Row row(CpuObservation observation, CpuFixtures.Resource resource) {
        return observation.rows()
            .stream()
            .filter(
                candidate -> candidate.itemKey()
                    .equals(resource.key()))
            .findFirst()
            .orElseThrow(AssertionError::new);
    }

    private static CpuObservation.Header header(CpuObservation observation) {
        CpuObservation.Header header = observation.header();
        assertNotNull(header, "A full observation has a header");
        return header;
    }

    @Test
    void fullReadDescribesEveryResourceAndTheHeaderWithoutMeasurements() {
        cpu.selectionMode = CpuSelectionMode.ALL;
        cpu.set(iron, 4, 2, 1)
            .set(gear, 0, 3, 0);

        CpuObservation observation = CpuReader.read(cpu, true);

        assertTrue(observation.full());
        assertTrue(
            observation.missing()
                .isEmpty());
        CpuObservation.Header header = header(observation);
        assertEquals(64, header.size());
        assertTrue(header.busy());
        assertEquals(CpuSelectionMode.ALL, header.selectionMode());
        assertFalse(header.supportsPause());
        assertFalse(header.paused());
        assertFalse(header.hasTrackingInfo());
        assertEquals(0, header.timeStarted());
        assertNotNull(header.finalOutput());
        assertEquals(
            gear.key(),
            header.finalOutput()
                .description()
                .itemKey());
        assertEquals(
            2,
            observation.rows()
                .size());
        CpuObservation.Amounts amounts = row(observation, iron).amounts();
        assertEquals(4, amounts.active());
        assertEquals(2, amounts.pending());
        assertEquals(1, amounts.stored());
        assertNull(amounts.tracking());
        assertEquals(0, amounts.craftedTotal());
        assertEquals(0, amounts.timeSpent(9000));
        assertFalse(amounts.crafting());
        assertEquals(
            "Display iron",
            observation.descriptions()
                .get(iron.key())
                .displayName());
        assertTrue(
            observation.unkeyed()
                .isEmpty());
    }

    @Test
    void fullAndRestrictedReadsUseOneBulkReadRegardlessOfResourceCount() {
        cpu.set(iron, 4, 2, 1)
            .set(gear, 0, 3, 0);
        CpuFixtures.Resource storedOnly = new CpuFixtures.Resource("stored-only");
        cpu.set(storedOnly, 0, 0, 9);
        CpuObservation full = CpuReader.read(cpu, true);

        assertEquals(1, cpu.bulkReads);
        assertEquals(
            3,
            full.rows()
                .size());
        assertEquals(new CpuObservation.Amounts(4, 2, 1, null), row(full, iron).amounts());
        assertEquals(new CpuObservation.Amounts(0, 3, 0, null), row(full, gear).amounts());
        assertEquals(new CpuObservation.Amounts(0, 0, 9, null), row(full, storedOnly).amounts());

        for (int i = 0; i < 100; i++) cpu.set(new CpuFixtures.Resource("extra-" + i), 1, 2, 3);
        CpuObservation restricted = CpuReader.read(cpu, cpu, Arrays.asList(iron, storedOnly));

        assertFalse(restricted.full());
        assertEquals(
            2,
            restricted.rows()
                .size());
        assertEquals(new CpuObservation.Amounts(4, 2, 1, null), row(restricted, iron).amounts());
        assertEquals(new CpuObservation.Amounts(0, 0, 9, null), row(restricted, storedOnly).amounts());
        assertEquals(2, cpu.bulkReads);
    }

    @Test
    void baseVariantsAreOnlyNormalizedWhenRequested() {
        // The identity registry is shared by the whole test run; a unique name keeps its cache out of the count.
        CpuFixtures.Resource iron = new CpuFixtures.Resource("iron-" + System.nanoTime());
        cpu.set(iron, 4, 0, 0);

        CpuObservation plain = CpuReader.read(cpu, false);

        assertEquals(0, iron.baseKeyReads.get());
        assertNull(
            plain.descriptions()
                .get(iron.key())
                .baseKey());
        assertEquals(
            4,
            row(plain, iron).amounts()
                .active());

        CpuObservation withBase = CpuReader.read(cpu, true);

        assertEquals(1, iron.baseKeyReads.get());
        assertNotNull(
            withBase.descriptions()
                .get(iron.key())
                .baseKey());
        CpuReader.read(cpu, true);
        assertEquals(1, iron.baseKeyReads.get(), "The retained description normalizes each identity once");
    }

    @Test
    void pausableCpuReportsItsPauseSupportAndState() {
        CpuFixtures.PausableCpu pausable = new CpuFixtures.PausableCpu(2);
        pausable.busy = true;
        pausable.paused = true;
        CpuObservation observation = CpuReader.read(pausable, true);
        assertTrue(header(observation).supportsPause());
        assertTrue(header(observation).paused());
        pausable.busy = false;
        assertFalse(header(CpuReader.read(pausable, true)).paused(), "An idle CPU is never paused");
    }

    @Test
    void idleCpuHasOnlyAHeader() {
        cpu.busy = false;
        cpu.set(iron, 1, 1, 1);

        CpuObservation observation = CpuReader.read(cpu, true);

        assertFalse(header(observation).busy());
        assertNull(header(observation).finalOutput());
        assertTrue(
            observation.rows()
                .isEmpty());
        assertTrue(
            observation.unkeyed()
                .isEmpty());
        assertEquals(0, cpu.bulkReads, "An idle CPU has no work to list");
    }

    @Test
    void resourceWithoutAStableIdentityIsReadAsAnUnkeyedRow() {
        cpu.set(iron, 1, 0, 0)
            .set(exotic, 7, 0, 0);

        CpuObservation observation = CpuReader.read(cpu, true);

        assertEquals(
            1,
            observation.rows()
                .size());
        assertEquals(
            1,
            observation.unkeyed()
                .size());
        CpuObservation.UnkeyedRow unkeyed = observation.unkeyed()
            .get(0);
        assertEquals(
            "Display exotic",
            unkeyed.description()
                .displayName());
        assertNull(
            unkeyed.description()
                .itemKey());
        assertEquals(
            7,
            unkeyed.amounts()
                .active());
    }

    @Test
    void trackedJobAddsMeasurementsAndANonProgressingWaitingMarker() {
        cpu.set(iron, 1, 0, 0);
        TestGridFixtures.track(grid);
        AE2JobTracker.addJob(cpu, grid, false, CraftingRequester.automation());
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        assertNotNull(info);
        info.timeStarted = 1000;
        info.timeSpentOn.put(iron, 300L);
        info.craftedTotal.put(iron, 8L);
        info.startedWaitingFor.put(iron, 4000L);
        info.timeSpentOn.put(gear, 50L);
        info.craftedTotal.put(gear, 2L);

        CpuObservation observation = CpuReader.read(cpu, true);

        CpuObservation.Header header = header(observation);
        assertTrue(header.hasTrackingInfo());
        assertEquals(1000, header.timeStarted());
        CpuObservation.Amounts running = row(observation, iron).amounts();
        assertEquals(8, running.craftedTotal());
        assertTrue(running.crafting());
        assertEquals(300 + (5000 - 4000), running.timeSpent(5000));
        assertEquals(300 + (9000 - 4000), running.timeSpent(9000));
        CpuObservation.Amounts finished = row(observation, gear).amounts();
        assertFalse(finished.crafting());
        assertEquals(50, finished.timeSpent(9000));
        assertEquals(0, finished.active(), "A measured resource without work still has a row");

        CpuObservation later = CpuReader.read(cpu, true);
        assertEquals(running, row(later, iron).amounts(), "Clock progress alone must not change the detached row");
        info.craftedTotal.put(iron, 9L);
        assertNotEquals(running, row(CpuReader.read(cpu, true), iron).amounts());
    }

    @Test
    void partialReadReturnsOnlyRequestedResourcesAndReportsEmptyOnesAsMissing() {
        cpu.set(iron, 4, 0, 0)
            .set(gear, 1, 0, 0);

        CpuObservation observation = CpuReader.read(cpu, cpu, Arrays.asList(iron, new CpuFixtures.Resource("tin")));

        assertFalse(observation.full());
        assertTrue(header(observation).busy());
        assertEquals(
            1,
            observation.rows()
                .size());
        assertEquals(
            4,
            row(observation, iron).amounts()
                .active());
        assertEquals(Collections.singleton(new CpuFixtures.Resource("tin").key()), observation.missing());
        assertEquals(1, cpu.bulkReads, "A restricted read takes the bulk lists once");
        assertTrue(
            observation.descriptions()
                .containsKey(iron.key()));
    }

    @Test
    void partialReadKeepsMeasuredResourcesWithoutWorkAndFollowsTheirWaitingState() {
        TestGridFixtures.track(grid);
        cpu.set(gear, 0, 0, 1);
        AE2JobTracker.addJob(cpu, grid, false, CraftingRequester.automation());
        AE2JobTracker.JobTrackingInfo info = AE2JobTracker.findActiveJob(cpu);
        assertNotNull(info);
        info.timeSpentOn.put(iron, 10L);
        info.startedWaitingFor.put(iron, 4000L);

        CpuObservation observation = CpuReader.read(cpu, cpu, Collections.singletonList(iron));

        assertTrue(
            observation.missing()
                .isEmpty());
        assertTrue(
            row(observation, iron).amounts()
                .crafting());
        System.gc();
        assertNotNull(
            AE2Controller.itemIdentities.resolve(iron.key()),
            "Measured rows retain action lookup even without native work");
        assertNotNull(AE2Controller.itemIdentities.resolve(gear.key()), "Pending/stored work remains owned");
    }

    @Test
    void partialReadOfAnUnkeyedResourceOrAnIdleCpuIsAFullRead() {
        cpu.set(iron, 1, 0, 0)
            .set(exotic, 3, 0, 0);

        CpuObservation withUnkeyed = CpuReader.read(cpu, cpu, Collections.singletonList(exotic));
        assertTrue(withUnkeyed.full());
        assertEquals(1, cpu.bulkReads, "The fallback decision is made before any native list is read");
        assertNotNull(withUnkeyed.header());
        assertEquals(
            1,
            withUnkeyed.unkeyed()
                .size());

        cpu.busy = false;
        CpuObservation idle = CpuReader.read(cpu, cpu, Collections.singletonList(iron));
        assertTrue(idle.full());
        assertFalse(header(idle).busy());
    }
}
