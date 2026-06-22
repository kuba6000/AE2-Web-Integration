package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.networking.storage.IStorageGrid;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.CompositeStackList;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.DispatchingMeInventory;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

@Mixin(value = IStorageGrid.class)
public interface AEStorageGridMixin extends IAEStorageGrid {

    @Override
    default IStackList web$getStorageList() {
        IStorageGrid grid = (IStorageGrid) (Object) this;
        return new CompositeStackList(
            (IStackList) (Object) grid.getItemInventory().getStorageList(),
            (IStackList) (Object) grid.getFluidInventory().getStorageList());
    }

    @Override
    default IAEMeInventoryItem web$getInventory() {
        IStorageGrid grid = (IStorageGrid) (Object) this;
        return new DispatchingMeInventory(
            (IAEMeInventoryItem) grid.getItemInventory(),
            (IAEMeInventoryItem) grid.getFluidInventory());
    }
}
