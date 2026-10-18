package pl.kuba6000.ae2webintegration.core.tracking.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Stock is an observed absolute amount; flow counts accepted operations between observations. */
@Desugar
public record StorageHistoryPoint(long observedAt, long generation, @NotNull Kind kind, @Nullable StableKey itemKey,
    long quantity, long input, long output, @Nullable Long flowFrom) {

    public enum Kind {
        STOCK,
        FLOW,
        OBSERVATION,
        FLOW_OBSERVATION,
        GAP
    }
}
