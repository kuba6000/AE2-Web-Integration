package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.AEApi;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.CompositeStackList;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.DispatchingMeInventory;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

/**
 * 1.12.2 — unified storage overList. Adds missing fluid channel (current branch is item-only).
 */
@Mixin(value = IStorageGrid.class)
public interface AEStorageGridMixin extends IAEStorageGrid {

    @Override
    default IStackList web$getStorageList() {
        IStorageGrid grid = (IStorageGrid) (Object) this;
        return new CompositeStackList(
            (IStackList) (Object) grid.getInventory(
                AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)).getStorageList(),
            (IStackList) (Object) grid.getInventory(
                AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class)).getStorageList());
    }

    @Override
    default IAEMeInventoryItem web$getInventory() {
        IStorageGrid grid = (IStorageGrid) (Object) this;
        return new DispatchingMeInventory(
            (IAEMeInventoryItem) grid.getInventory(
                AEApi.instance().storage().getStorageChannel(IItemStorageChannel.class)),
            (IAEMeInventoryItem) grid.getInventory(
                AEApi.instance().storage().getStorageChannel(IFluidStorageChannel.class)));
    }
}
