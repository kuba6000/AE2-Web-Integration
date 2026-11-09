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

/** Published immutable state; generation distinguishes replacement native grids with the same saved identity. */
@Desugar
public record StorageSnapshot(long generation, long revision, long observedAt, boolean stockComplete,
    boolean flowComplete, boolean persistenceComplete, @NotNull List<StorageObservation.Row> rows,
    @NotNull Map<StableKey, ResourceDescriptor> descriptions, @NotNull List<StorageObservation.UnkeyedRow> unkeyed,
    long lossEpoch) {

    public StorageSnapshot(long generation, long revision, long observedAt, boolean stockComplete, boolean flowComplete,
        boolean persistenceComplete, @NotNull List<StorageObservation.Row> rows,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StorageObservation.UnkeyedRow> unkeyed) {
        this(
            generation,
            revision,
            observedAt,
            stockComplete,
            flowComplete,
            persistenceComplete,
            rows,
            descriptions,
            unkeyed,
            0);
    }

    public StorageSnapshot {
        descriptions = Collections.unmodifiableMap(new HashMap<>(descriptions));
        unkeyed = Collections.unmodifiableList(new ArrayList<>(unkeyed));
        rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }
}
