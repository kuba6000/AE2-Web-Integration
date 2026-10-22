package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.cpu.CpuObservation;
import pl.kuba6000.ae2webintegration.core.cpu.CpuTopic;
import pl.kuba6000.ae2webintegration.core.http.dto.CpuResource;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** The detail response's resource list: display order and the timing ratios derived from detached rows. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CpuItemsTest {

    private static final CpuObservation.Header TRACKED = new CpuObservation.Header(
        64,
        true,
        null,
        false,
        false,
        null,
        true,
        1000);
    private static final CpuObservation.Header UNTRACKED = new CpuObservation.Header(
        64,
        true,
        null,
        false,
        false,
        null,
        false,
        0);
    private final Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();

    private CpuObservation.Row row(long id, CpuObservation.Amounts amounts) {
        StableKey key = TestGridFixtures.key(id);
        descriptions.put(key, new ResourceDescriptor("test", "r" + id, 0, 0, "R" + id, ResourceType.ITEM, key, null));
        return new CpuObservation.Row(key, amounts);
    }

    private static CpuObservation.Amounts work(long active, long pending, long stored) {
        return new CpuObservation.Amounts(active, pending, stored, null);
    }

    private static CpuObservation.Amounts measured(long crafted, long completed, long waitingSince) {
        return new CpuObservation.Amounts(0, 0, 0, new CpuObservation.Tracking(crafted, completed, waitingSince));
    }

    private static CpuResource named(List<CpuResource> items, String path) {
        return items.stream()
            .filter(item -> path.equals(item.registryPath))
            .findFirst()
            .orElseThrow(AssertionError::new);
    }

    @Test
    void activeResourcesComeFirstThenPendingThenStoredEachByDescendingAmount() {
        List<CpuObservation.Row> rows = Arrays
            .asList(row(1, work(0, 0, 9)), row(2, work(0, 3, 0)), row(3, work(2, 0, 0)), row(4, work(7, 0, 0)));
        CpuObservation.UnkeyedRow unkeyed = new CpuObservation.UnkeyedRow(
            new ResourceDescriptor("test", "u", 0, 0, "U", ResourceType.OTHER, null, null),
            work(0, 5, 0));

        List<CpuResource> items = CpuTopic
            .items(rows, descriptions, Collections.singletonList(unkeyed), UNTRACKED, 5000, null);

        List<String> order = new ArrayList<>();
        for (CpuResource item : items) order.add(item.registryPath);
        assertEquals(Arrays.asList("r4", "r3", "u", "r2", "r1"), order);
    }

    @Test
    void trackedRowsGetTimeSharesAndRatesAsOfTheResponseInstant() {
        List<CpuObservation.Row> rows = Arrays
            .asList(row(1, measured(8, 300, 0)), row(2, measured(2, 0, 1500)), row(3, work(1, 0, 0)));

        List<CpuResource> items = CpuTopic.items(rows, descriptions, Collections.emptyList(), TRACKED, 2500, null);

        CpuResource finished = named(items, "r1");
        assertEquals(300, finished.timeSpentCrafting);
        assertFalse(finished.crafting);
        assertEquals(300d / 1300d, finished.shareInCraftingTime, 0.000001);
        assertEquals(0.2, finished.shareInCraftingTimeCombined, 0.000001);
        assertEquals(8 / 0.3, finished.craftsPerSec, 0.000001);
        CpuResource running = named(items, "r2");
        assertEquals(1000, running.timeSpentCrafting);
        assertTrue(running.crafting);
        assertEquals(1000d / 1300d, running.shareInCraftingTime, 0.000001);
        assertEquals(2d, running.craftsPerSec, 0.000001);
        CpuResource unmeasured = named(items, "r3");
        assertEquals(0, unmeasured.timeSpentCrafting);
        assertEquals(0d, unmeasured.shareInCraftingTime);
        assertEquals(0d, unmeasured.craftsPerSec);
    }

    @Test
    void shareCombinedIsCappedAndAMeasuredResourceWithoutTimeHasAFullShare() {
        List<CpuObservation.Row> rows = Arrays.asList(row(1, measured(1, 5000, 0)), row(2, measured(0, 0, 0)));

        List<CpuResource> items = CpuTopic.items(rows, descriptions, Collections.emptyList(), TRACKED, 2000, null);

        assertEquals(1d, named(items, "r1").shareInCraftingTimeCombined);
        assertEquals(0d, named(items, "r2").shareInCraftingTime);

        List<CpuResource> idle = CpuTopic.items(
            Collections.singletonList(row(3, measured(0, 0, 0))),
            descriptions,
            Collections.emptyList(),
            TRACKED,
            2000,
            null);
        assertEquals(1d, named(idle, "r3").shareInCraftingTime);
        assertEquals(0d, named(idle, "r3").shareInCraftingTimeCombined);
    }

    @Test
    void anElapsedTimeThatIsNotPositiveYieldsNoCombinedShare() {
        List<CpuObservation.Row> rows = Collections.singletonList(row(1, measured(4, 500, 0)));

        List<CpuResource> items = CpuTopic.items(rows, descriptions, Collections.emptyList(), TRACKED, 500, null);

        assertEquals(0d, items.get(0).shareInCraftingTimeCombined);
        assertEquals(4 / 0.5, items.get(0).craftsPerSec, 0.000001);
    }
}
