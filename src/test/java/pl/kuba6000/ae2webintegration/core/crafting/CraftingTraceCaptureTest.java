package pl.kuba6000.ae2webintegration.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingTraceCaptureTest {

    @Test
    void keepsRequestsContributionsAndTasksInTraversalOrderWithStableIds() {
        CraftingTraceCapture trace = TraceFixtures.gears();
        assertEquals(
            6,
            trace.requests()
                .size());
        assertEquals(Integer.valueOf(0), trace.root());
        assertFalse(trace.simulated());
        assertEquals(
            12,
            trace.contributions()
                .size());
        assertEquals(
            Arrays.asList(
                ContributionKind.STORAGE,
                ContributionKind.CRAFT,
                ContributionKind.STORAGE,
                ContributionKind.CRAFT,
                ContributionKind.STORAGE,
                ContributionKind.EMITTED,
                ContributionKind.CRAFT,
                ContributionKind.STORAGE,
                ContributionKind.MISSING,
                ContributionKind.UNKNOWN,
                ContributionKind.REUSED,
                ContributionKind.CRAFT),
            kinds(trace));
        // Leaf contributions carry no task; every craft names one, and a shared task is the same ID twice.
        assertNull(
            trace.contributions()
                .get(0)
                .task());
        assertEquals(
            Integer.valueOf(0),
            trace.contributions()
                .get(1)
                .task());
        assertEquals(
            Integer.valueOf(1),
            trace.contributions()
                .get(3)
                .task());
        assertEquals(
            Integer.valueOf(1),
            trace.contributions()
                .get(11)
                .task());
        assertEquals(
            3,
            trace.tasks()
                .size());
        assertEquals(
            3,
            trace.tasks()
                .get(1)
                .crafts());
        assertTrue(
            trace.tasks()
                .stream()
                .allMatch(CraftingTraceCapture.Task::complete));
        assertTrue(
            trace.limits()
                .isEmpty());
    }

    @Test
    void entryLimitStopsBeforeReadingAnotherNativeDescriptionAndCannotFinishTasks() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(3, 1000);
        int resource = trace.addResource(() -> TraceFixtures.descriptor("item"));
        int request = trace.addRequest(resource, 5);
        int task = trace.addTask(1);
        assertFalse(trace.hasCapacity());
        assertEquals(-1, trace.addResource(() -> { throw new AssertionError("native read after the limit"); }));
        assertFalse(trace.addContribution(request, ContributionKind.STORAGE, 1));
        assertEquals(-1, trace.addRequest(resource, 1));
        assertThrows(IllegalStateException.class, () -> trace.finishTask(task));
        CraftingTraceCapture built = trace.build();
        assertTrue(
            built.limits()
                .contains(CraftingRecipeCapture.Limit.ENTRIES));
        assertEquals(
            1,
            built.requests()
                .size());
        assertEquals(
            0,
            built.contributions()
                .size());
        assertFalse(
            built.tasks()
                .get(0)
                .complete());
    }

    @Test
    void descriptionLimitRejectsAnOversizedResourceWithoutTruncatingIt() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(20, 4);
        assertEquals(0, trace.addResource(() -> TraceFixtures.descriptor("same")));
        assertEquals(-1, trace.addResource(() -> TraceFixtures.descriptor("oversized")));
        CraftingTraceCapture built = trace.build();
        assertEquals(
            1,
            built.resources()
                .size());
        assertTrue(
            built.limits()
                .contains(CraftingRecipeCapture.Limit.DESCRIPTION_CHARACTERS));
    }

    @Test
    void aSharedTaskStaysReferenceableAfterItsDeclarationIsFinished() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(50, 100);
        int item = trace.addResource(() -> TraceFixtures.descriptor("item"));
        int first = trace.addRequest(item, 2);
        int second = trace.addRequest(item, 3);
        int task = trace.addTask(5);
        assertTrue(trace.addCraft(first, 2, task));
        trace.addOutput(task, item, 1, true);
        trace.finishTask(task);
        assertTrue(trace.addCraft(second, 3, task));
        assertThrows(IllegalStateException.class, () -> trace.addOutput(task, item, 1, null));
        assertThrows(IllegalStateException.class, () -> trace.addChildRequest(task, first));
        assertThrows(IllegalStateException.class, () -> trace.addInput(task, item, 1));
        assertEquals(
            1,
            trace.build()
                .tasks()
                .size());
    }

    @Test
    void rejectsDanglingReferencesAMisclassifiedCraftAndNegativeQuantities() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(50, 100);
        int item = trace.addResource(() -> TraceFixtures.descriptor("item"));
        int request = trace.addRequest(item, 1);
        int task = trace.addTask(1);
        assertThrows(IndexOutOfBoundsException.class, () -> trace.addRequest(9, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> trace.addContribution(9, ContributionKind.STORAGE, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> trace.addCraft(request, 1, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> trace.addChildRequest(task, 9));
        assertThrows(IndexOutOfBoundsException.class, () -> trace.addInput(task, 9, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> trace.root(9, false));
        assertThrows(IllegalArgumentException.class, () -> trace.addContribution(request, ContributionKind.CRAFT, 1));
        assertThrows(
            IllegalArgumentException.class,
            () -> trace.addContribution(request, ContributionKind.MISSING, -1));
    }

    @Test
    void immutableCaptureOwnsItsTablesEvenWhenConstructedDirectly() {
        List<ResourceDescriptor> resources = new ArrayList<>();
        resources.add(TraceFixtures.descriptor("original"));
        CraftingTraceCapture snapshot = new CraftingTraceCapture(
            null,
            false,
            resources,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptySet());
        resources.clear();
        assertEquals(
            1,
            snapshot.resources()
                .size());
        assertThrows(
            UnsupportedOperationException.class,
            () -> snapshot.resources()
                .clear());
    }

    private static List<ContributionKind> kinds(CraftingTraceCapture trace) {
        List<ContributionKind> kinds = new ArrayList<>();
        for (CraftingTraceCapture.Contribution contribution : trace.contributions()) kinds.add(contribution.kind());
        return kinds;
    }
}
