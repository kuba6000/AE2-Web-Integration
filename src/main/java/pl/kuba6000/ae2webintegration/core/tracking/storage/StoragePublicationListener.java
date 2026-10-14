package pl.kuba6000.ae2webintegration.core.tracking.storage;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Detached publication notifications. Retirement can arrive concurrently from the lifecycle owner. */
public interface StoragePublicationListener {

    void published(@NotNull StableKey gridKey, @NotNull StorageSnapshot snapshot);

    void retired(@NotNull StableKey gridKey, long generation);

    /** Transient coverage loss; a later coherent snapshot can resume this generation at the newer epoch. */
    default void retired(@NotNull StableKey gridKey, long generation, long lossEpoch) {
        retired(gridKey, generation);
    }
}
