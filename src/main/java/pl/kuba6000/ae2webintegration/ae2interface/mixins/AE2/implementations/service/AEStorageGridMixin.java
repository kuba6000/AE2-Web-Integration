package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.AEApi;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IItemStorageChannel;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

@Mixin(value = IStorageGrid.class)
public interface AEStorageGridMixin extends IAEStorageGrid {

    @Override
    public default IItemList web$getItemStorageList() {
        return (IItemList) (Object) ((IStorageGrid) (Object) this).getInventory(
            AEApi.instance()
                .storage()
                .getStorageChannel(IItemStorageChannel.class))
            .getStorageList();
    }

    @Override
    public default IAEMeInventoryItem web$getItemInventory() {
        return (IAEMeInventoryItem) ((IStorageGrid) (Object) this).getInventory(
            AEApi.instance()
                .storage()
                .getStorageChannel(IItemStorageChannel.class));
    }
}
