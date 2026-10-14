package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** One detached, completed server-thread observation; quantities are absolute, flow is gross. */
@Desugar
public record StorageObservation(long observedAt, boolean full, boolean flowComplete, boolean stockObserved,
    @NotNull List<Row> rows, @NotNull List<Flow> flows) {

    public StorageObservation(long observedAt, boolean full, boolean flowComplete, @NotNull List<Row> rows,
        @NotNull List<Flow> flows) {
        this(observedAt, full, flowComplete, true, rows, flows);
    }

    public StorageObservation {
        rows = Collections.unmodifiableList(new ArrayList<>(rows));
        flows = Collections.unmodifiableList(new ArrayList<>(flows));
    }

    @Desugar
    public record Row(@NotNull ResourceSnapshot resource, boolean craftable) {}

    @Desugar
    public record Flow(@NotNull ResourceSnapshot resource, long input, long output) {}
}
