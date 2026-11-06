package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;

/** Binds the network inventory to its owning storage service's capture lifecycle. */
public interface IStorageCaptureOwner {

    void web$setStorageCapture(@Nullable StorageCapture capture);
}
