package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** One detached, completed server-thread observation; quantities are absolute, flow is gross. */
@Desugar
public record StorageObservation(long observedAt, boolean full, boolean flowComplete, boolean stockObserved,
    @NotNull List<Row> rows, @NotNull List<Flow> flows, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
    @NotNull List<UnkeyedRow> unkeyed) {

    public StorageObservation(long observedAt, boolean full, boolean flowComplete, @NotNull List<Row> rows,
        @NotNull List<Flow> flows, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<UnkeyedRow> unkeyed) {
        this(observedAt, full, flowComplete, true, rows, flows, descriptions, unkeyed);
    }

    public StorageObservation {
        descriptions = Collections.unmodifiableMap(new HashMap<>(descriptions));
        unkeyed = Collections.unmodifiableList(new ArrayList<>(unkeyed));
        rows = Collections.unmodifiableList(new ArrayList<>(rows));
        flows = Collections.unmodifiableList(new ArrayList<>(flows));
    }

    @Desugar
    public record Row(@NotNull StableKey itemKey, long quantity, boolean craftable) {}

    @Desugar
    public record Flow(@NotNull StableKey itemKey, long input, long output, long inputOperations,
        long outputOperations) {}

    @Desugar
    public record UnkeyedRow(@NotNull ResourceSnapshot resource, boolean craftable) {}
}
