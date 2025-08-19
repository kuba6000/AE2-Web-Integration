package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IItemStack;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    public default IItemStack web$extractItems(IItemStack stack, AEActionable mode, IAEGrid grid) {
        return (IItemStack) ((IMEInventory) (Object) this).extractItems(
            (IAEStack) stack,
            mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE,
            (BaseActionSource) grid.web$getPlayerSource());
    }

    @Override
    public default IItemStack web$getAvailableItem(IItemStack stack) {
        return (IItemStack) ((IMEInventory) (Object) this).getAvailableItem((IAEStack) stack);
    }
}
