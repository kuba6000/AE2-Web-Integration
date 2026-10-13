package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** Published immutable state; generation distinguishes replacement native grids with the same saved identity. */
@Desugar
public record StorageSnapshot(long generation, long revision, long observedAt, boolean stockComplete,
    boolean flowComplete, boolean persistenceComplete, @NotNull List<StorageObservation.Row> rows) {

    public StorageSnapshot {
        rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }
}
