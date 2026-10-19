package pl.kuba6000.ae2webintegration.core.cpu;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/**
 * One detached, completed server-thread read of a crafting CPU. A full observation holds the header and every
 * resource; a restricted one holds only the requested resources, with those that no longer have any work in
 * {@code missing}, and no header.
 */
@Desugar
public record CpuObservation(long observedAt, boolean full, @Nullable Header header, @NotNull List<Row> rows,
    @NotNull Set<StableKey> missing, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
    @NotNull List<UnkeyedRow> unkeyed) {

    public CpuObservation {
        rows = Collections.unmodifiableList(rows);
        missing = Collections.unmodifiableSet(missing);
        descriptions = Collections.unmodifiableMap(descriptions);
        unkeyed = Collections.unmodifiableList(unkeyed);
    }

    /**
     * CPU state that changes rarely. Elapsed job time is derived from {@code timeStarted}, so comparing headers
     * ignores clock progress.
     */
    @Desugar
    public record Header(long size, boolean busy, @Nullable CpuSelectionMode selectionMode, boolean supportsPause,
        boolean paused, @Nullable ResourceSnapshot finalOutput, boolean hasTrackingInfo, long timeStarted) {

        /** Job elapsed time at {@code at}; zero without tracking. */
        public long timeElapsed(long at) {
            return hasTrackingInfo ? at - timeStarted : 0;
        }
    }

    /**
     * Measurements of the tracked job for one resource. {@code waitingSince} is the server clock time the current
     * waiting interval began, or zero between intervals; it only changes when an interval starts.
     */
    @Desugar
    public record Tracking(long craftedTotal, long completedMillis, long waitingSince) {}

    /** Absolute work of one resource; {@code tracking} is null when the job does not measure it. */
    @Desugar
    public record Amounts(long active, long pending, long stored, @Nullable Tracking tracking) {

        public long craftedTotal() {
            return tracking == null ? 0 : tracking.craftedTotal();
        }

        /** Whether the job is currently waiting for this resource. */
        public boolean crafting() {
            return tracking != null && tracking.waitingSince() != 0;
        }

        /** Completed waiting time plus the running interval, evaluated at {@code at}. */
        public long timeSpent(long at) {
            if (tracking == null) return 0;
            return tracking.completedMillis() + (crafting() ? at - tracking.waitingSince() : 0);
        }
    }

    @Desugar
    public record Row(@NotNull StableKey itemKey, @NotNull Amounts amounts) {}

    /** A resource whose native identity cannot be encoded; the live view only ever replaces all of them together. */
    @Desugar
    public record UnkeyedRow(@NotNull ResourceDescriptor description, @NotNull Amounts amounts) {}
}
