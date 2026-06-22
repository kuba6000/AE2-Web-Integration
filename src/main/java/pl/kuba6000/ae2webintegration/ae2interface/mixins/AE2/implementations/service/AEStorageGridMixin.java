package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.AEApi;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.channels.IFluidStorageChannel;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IItemList;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.CompositeStackList;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.DispatchingMeInventory;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.FluidStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

@Mixin(value = IStorageGrid.class)
public interface AEStorageGridMixin extends IAEStorageGrid {

    @Override
    default IStackList web$getStorageList() {
        IStorageGrid grid = (IStorageGrid) (Object) this;
        IStackList items = (IStackList) (Object) grid.getInventory(
            AEApi.instance()
                .storage()
                .getStorageChannel(IItemStorageChannel.class))
            .getStorageList();
        IItemList<IAEFluidStack> fluidList = grid.getInventory(
            AEApi.instance()
                .storage()
                .getStorageChannel(IFluidStorageChannel.class))
            .getStorageList();
        return new CompositeStackList(items, new FluidStackList(fluidList));
    }

    @Override
    default IAEMeInventoryItem web$getInventory() {
        IStorageGrid grid = (IStorageGrid) (Object) this;
        return new DispatchingMeInventory(
            (IAEMeInventoryItem) grid.getInventory(
                AEApi.instance()
                    .storage()
                    .getStorageChannel(IItemStorageChannel.class)),
            (IAEMeInventoryItem) grid.getInventory(
                AEApi.instance()
                    .storage()
                    .getStorageChannel(IFluidStorageChannel.class)));
    }
}
