package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageObservation;

/**
 * Consumer of complete stock reads, called on the server thread inside {@link StorageTrackingCoordinator#tick}. It
 * must not block, read native state or throw.
 */
public interface StockObservationListener {

    /** A complete, successful read of the grid's storage. Quantities are absolute; absent resources are zero. */
    void stockObserved(@NotNull StableKey grid, @NotNull StorageObservation observation);

    /** The grid's storage could not be read; quantities from earlier observations are no longer trustworthy. */
    void stockReadFailed(@NotNull StableKey grid, @NotNull RuntimeException failure);
}
