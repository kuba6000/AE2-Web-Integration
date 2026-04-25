package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    public default IStack web$extractItems(IStack stack, AEActionable mode, IAEGrid grid) {
        return (IStack) ((IMEInventory) (Object) this).extractItems(
            (IAEStack) stack,
            mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE,
            (BaseActionSource) grid.web$getPlayerSource());
    }

    @Override
    public default IStack web$getAvailableItem(IStack stack) {
        return (IStack) ((IMEInventory) (Object) this).getAvailableItem((IAEStack) stack);
    }
}
