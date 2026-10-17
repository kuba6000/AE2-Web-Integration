package pl.kuba6000.ae2webintegration.core.interfaces.service;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageCapture;

public interface IAEStorageGrid {

    IStackList web$getStorageList();

    IAEMeInventoryItem web$getInventory();

    /** Complete authoritative read; adapters with delta-only caches must freshly enumerate here. */
    default IStackList web$getStorageSnapshot() {
        return web$getStorageList();
    }

    @Nullable
    StorageCapture web$getStorageCapture();

    void web$setStorageCapture(@Nullable StorageCapture capture);

}
