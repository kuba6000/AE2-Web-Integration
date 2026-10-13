package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record StorageHistory(@NotNull List<StorageHistoryPoint> points, boolean complete, boolean truncated) {

    public StorageHistory {
        points = Collections.unmodifiableList(new ArrayList<>(points));
    }
}
