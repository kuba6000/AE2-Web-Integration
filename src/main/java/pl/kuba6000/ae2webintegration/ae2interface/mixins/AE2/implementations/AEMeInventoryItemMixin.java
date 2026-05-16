package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.IStack;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    default IStack web$extractItems(IStack stack, AEActionable mode, IAEGrid grid) {
        return (IStack) ((IMEInventory) (Object) this).extractItems(
            (IAEStack) stack,
            mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE,
            (IActionSource) grid.web$getPlayerSource());
    }

    @Override
    default long web$extractItems(IAEKey stack, long amount, AEActionable mode, IAEGrid grid) {
        IAEItemStack template = (IAEItemStack) stack;
        IAEItemStack templateCopy = template.copy();
        templateCopy.setStackSize(amount);
        IAEItemStack extracted = (IAEItemStack) ((IMEInventory) (Object) this).extractItems(
            templateCopy,
            mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE,
            (IActionSource) grid.web$getPlayerSource());
        return extracted != null ? extracted.getStackSize() : 0L;
    }

    @Override
    default IStack web$getAvailableItem(IStack stack) {
        // Extract with simulation, no action source (grid not available in the new interface)
        return (IStack) ((IMEInventory) (Object) this).extractItems((IAEStack) stack, Actionable.SIMULATE, null);
    }

    @Override
    default long web$getAvailableItem(IAEKey stack, IAEGrid grid) {
        return web$extractItems(stack, 1, AEActionable.SIMULATE, grid);
    }
}
