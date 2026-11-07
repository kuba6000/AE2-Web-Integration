package pl.kuba6000.ae2webintegration.core.tracking.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Stock is an observed absolute amount; flow records transferred quantities and accepted operation counts. */
@Desugar
public record StorageHistoryPoint(long observedAt, long generation, @NotNull Kind kind, @Nullable StableKey itemKey,
    long quantity, long input, long output, long inputOperations, long outputOperations, @Nullable Long flowFrom) {

    public enum Kind {
        STOCK,
        FLOW,
        OBSERVATION,
        FLOW_OBSERVATION,
        GAP
    }
}
