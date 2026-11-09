package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Builds detached observations from readable resource fixtures. */
public final class StorageObservationFixtures {

    private StorageObservationFixtures() {}

    @Desugar
    public record Row(ResourceSnapshot resource, boolean craftable) {}

    @Desugar
    public record Flow(ResourceSnapshot resource, long input, long output) {}

    public static StorageObservation observation(long at, boolean full, boolean complete, List<Row> rows,
        List<Flow> flows) {
        return observation(at, full, complete, true, rows, flows);
    }

    public static StorageObservation observation(long at, boolean full, boolean complete, boolean stock,
        List<Row> resources, List<Flow> transfers) {
        Map<StableKey, ResourceDescriptor> descriptions = new HashMap<>();
        List<StorageObservation.Row> rows = new ArrayList<>();
        List<StorageObservation.Flow> flows = new ArrayList<>();
        List<StorageObservation.UnkeyedRow> unkeyed = new ArrayList<>();
        for (Row row : resources) {
            ResourceDescriptor description = row.resource()
                .description();
            if (description.itemKey() == null)
                unkeyed.add(new StorageObservation.UnkeyedRow(row.resource(), row.craftable()));
            else {
                descriptions.put(description.itemKey(), description);
                rows.add(
                    new StorageObservation.Row(
                        description.itemKey(),
                        row.resource()
                            .quantity(),
                        row.craftable()));
            }
        }
        for (Flow flow : transfers) {
            ResourceDescriptor description = flow.resource()
                .description();
            if (description.itemKey() == null) continue;
            descriptions.put(description.itemKey(), description);
            flows.add(
                new StorageObservation.Flow(
                    description.itemKey(),
                    flow.input(),
                    flow.output(),
                    flow.input() > 0 ? 1 : 0,
                    flow.output() > 0 ? 1 : 0));
        }
        return new StorageObservation(at, full, complete, stock, rows, flows, descriptions, unkeyed);
    }
}
