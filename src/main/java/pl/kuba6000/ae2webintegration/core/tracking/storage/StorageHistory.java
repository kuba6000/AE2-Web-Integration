package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

/** Baseline is last-known stock context outside the point limit; null means no retained observation, not zero. */
@Desugar
public record StorageHistory(@NotNull List<StorageHistoryPoint> points, boolean complete, boolean truncated,
    @Nullable StorageHistoryPoint baseline) {

    public StorageHistory(@NotNull List<StorageHistoryPoint> points, boolean complete, boolean truncated) {
        this(points, complete, truncated, null);
    }

    public StorageHistory {
        points = Collections.unmodifiableList(new ArrayList<>(points));
    }
}
