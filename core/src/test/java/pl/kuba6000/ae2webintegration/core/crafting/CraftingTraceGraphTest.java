package pl.kuba6000.ae2webintegration.core.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.crafting.CraftingTraceCapture.ContributionKind;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CraftingTraceGraphTest {

    @Test
    void listsContributionsAndChildRequestsInNativeOrderAndSharesATaskBetweenRequests() {
        CraftingTraceGraph graph = CraftingTraceGraph.from(TraceFixtures.gears());
        assertEquals(0, graph.root());
        assertEquals(Arrays.asList(0, 1), graph.contributions(0));
        assertEquals(Arrays.asList(2, 3), graph.contributions(1));
        assertEquals(Arrays.asList(4, 5, 6), graph.contributions(2));
        assertEquals(Collections.singletonList(7), graph.contributions(3));
        assertEquals(Arrays.asList(8, 9), graph.contributions(4));
        assertEquals(Arrays.asList(10, 11), graph.contributions(5));
        assertEquals(Arrays.asList(0, 1, 2), graph.taskRequests(0));
        assertEquals(Collections.singletonList(3), graph.taskRequests(1));
        assertEquals(Collections.singletonList(4), graph.taskRequests(2));
        // Task 1 serves the gear requests R1 and R5; each keeps its own crafted amount.
        CraftingTraceCapture capture = graph.capture();
        assertEquals(
            Integer.valueOf(1),
            capture.contributions()
                .get(3)
                .task());
        assertEquals(
            Integer.valueOf(1),
            capture.contributions()
                .get(11)
                .task());
        assertEquals(
            4,
            capture.contributions()
                .get(3)
                .amount());
        assertEquals(
            2,
            capture.contributions()
                .get(11)
                .amount());
        assertEquals(Arrays.asList(1, 2), graph.outputs(1));
        assertEquals(Collections.singletonList(3), graph.inputs(1));
    }

    @Test
    void missingAmountsAreOccurrenceSumsAndNeverSplitByBranch() {
        CraftingTraceGraph graph = CraftingTraceGraph.from(TraceFixtures.gears());
        assertTrue(graph.missingComplete());
        assertEquals(1, graph.missingResourceCount());
        assertEquals(Long.valueOf(2), graph.missingAmount(TraceFixtures.INGOT));
        assertEquals(Long.valueOf(0), graph.missingAmount(TraceFixtures.GEAR));
    }

    @Test
    void missingOnlyKeepsPathsToMissingContributionsThroughSharedTasks() {
        CraftingTraceGraph graph = CraftingTraceGraph.from(TraceFixtures.gears());
        // Only the craft contributions and the missing leaf survive; storage, emission, reuse and the
        // lubricant branch (nothing missing under it) are dropped.
        assertEquals(Collections.singletonList(1), graph.contributions(0, true));
        assertEquals(Collections.singletonList(3), graph.contributions(1, true));
        assertEquals(Collections.singletonList(6), graph.contributions(2, true));
        assertEquals(Collections.emptyList(), graph.contributions(3, true));
        assertEquals(Collections.singletonList(8), graph.contributions(4, true));
        assertEquals(Collections.singletonList(11), graph.contributions(5, true));
        assertEquals(Arrays.asList(0, 1), graph.taskRequests(0, true));
        assertEquals(Collections.singletonList(3), graph.taskRequests(1, true));
        assertEquals(Collections.singletonList(4), graph.taskRequests(2, true));
        assertEquals(graph.contributions(0), graph.contributions(0, false));
    }

    @Test
    void recursionThroughASharedTaskIsFiniteAndStillReachesMissingPaths() {
        CraftingTraceGraph graph = CraftingTraceGraph.from(TraceFixtures.recursion());
        assertEquals(Collections.singletonList(0), graph.contributions(0));
        assertEquals(Collections.singletonList(2), graph.contributions(2));
        assertEquals(Collections.singletonList(0), graph.taskRequests(0));
        assertEquals(Collections.singletonList(1), graph.taskRequests(1));
        assertEquals(0, graph.missingResourceCount());
        assertEquals(Collections.emptyList(), graph.contributions(0, true));
        assertEquals(Collections.emptyList(), graph.taskRequests(0, true));
    }

    @Test
    void missingInsideARecursiveCycleMarksEveryPathBackToTheRoot() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(50, 100);
        int a = trace.addResource(() -> TraceFixtures.descriptor("A"));
        int root = trace.addRequest(a, 2);
        trace.root(root, false);
        int task = trace.addTask(2);
        trace.addCraft(root, 2, task);
        int inner = trace.addRequest(a, 1);
        trace.addChildRequest(task, inner);
        trace.finishTask(task);
        trace.addCraft(inner, 1, task);
        trace.addContribution(inner, ContributionKind.MISSING, 1);
        CraftingTraceGraph graph = CraftingTraceGraph.from(trace.build());
        assertEquals(Collections.singletonList(0), graph.contributions(root, true));
        assertEquals(Arrays.asList(1, 2), graph.contributions(inner, true));
        assertEquals(Collections.singletonList(0), graph.taskRequests(task, true));
    }

    @Test
    void truncatedOrUnfinishedTracesCannotEstablishThatNothingIsMissing() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(4, 100);
        int item = trace.addResource(() -> TraceFixtures.descriptor("item"));
        int other = trace.addResource(() -> TraceFixtures.descriptor("other"));
        int request = trace.addRequest(item, 1);
        trace.root(request, false);
        trace.addContribution(request, ContributionKind.MISSING, 1);
        assertFalse(trace.addContribution(request, ContributionKind.STORAGE, 1));
        CraftingTraceGraph graph = CraftingTraceGraph.from(trace.build());
        assertFalse(graph.missingComplete());
        assertEquals(Long.valueOf(1), graph.missingAmount(item));
        assertNull(graph.missingAmount(other));
    }

    @Test
    void overflowingMissingSumRetainsShortagePathsWithoutInventingAnAmount() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(20, 100);
        int item = trace.addResource(() -> TraceFixtures.descriptor("item"));
        int request = trace.addRequest(item, Long.MAX_VALUE);
        trace.root(request, true);
        trace.addContribution(request, ContributionKind.MISSING, Long.MAX_VALUE);
        trace.addContribution(request, ContributionKind.MISSING, 1);
        trace.addContribution(request, ContributionKind.MISSING, 1);
        CraftingTraceGraph graph = CraftingTraceGraph.from(trace.build());
        assertNull(graph.missingAmount(item));
        assertTrue(graph.isMissing(item));
        assertFalse(graph.missingComplete());
        assertEquals(1, graph.missingResourceCount());
        assertEquals(Arrays.asList(0, 1, 2), graph.contributions(request, true));
    }

    @Test
    void aTraceThatNeverReachedItsRootCannotBeIndexed() {
        CraftingTraceCapture.Builder trace = new CraftingTraceCapture.Builder(0, 100);
        assertEquals(-1, trace.addResource(() -> TraceFixtures.descriptor("item")));
        CraftingTraceCapture capture = trace.build();
        assertNull(capture.root());
        assertThrows(IllegalArgumentException.class, () -> CraftingTraceGraph.from(capture));
    }
}
