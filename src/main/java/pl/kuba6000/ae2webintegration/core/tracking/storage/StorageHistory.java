package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Baseline is last-known stock context outside the point limit; null means no retained observation, not zero. */
@Desugar
public record StorageHistory(@NotNull List<StorageHistoryPoint> points,
    @NotNull Map<StableKey, ResourceDescriptor> descriptions, boolean complete, boolean truncated,
    @Nullable StorageHistoryPoint baseline) {

    public StorageHistory(@NotNull List<StorageHistoryPoint> points,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, boolean complete, boolean truncated) {
        this(points, descriptions, complete, truncated, null);
    }

    public StorageHistory {
        descriptions = Collections.unmodifiableMap(new HashMap<>(descriptions));
        points = Collections.unmodifiableList(new ArrayList<>(points));
    }
}
