package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    default long web$extractItems(IAEKey key, long amount, AEActionable mode, IAEGrid grid) {
        Actionable actionable = mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE;
        IActionSource source = (IActionSource) grid.web$getPlayerSource();
        if (key.web$isFluid()) {
            IAEFluidStack template = ((IAEFluidStack) (Object) key).copy();
            template.setStackSize(amount);
            IAEFluidStack extracted = (IAEFluidStack) ((IMEInventory) (Object) this)
                .extractItems(template, actionable, source);
            return extracted == null ? 0L : extracted.getStackSize();
        }
        IAEItemStack template = ((IAEItemStack) (Object) key).copy();
        template.setStackSize(amount);
        IAEItemStack extracted = (IAEItemStack) ((IMEInventory) (Object) this)
            .extractItems(template, actionable, source);
        return extracted == null ? 0L : extracted.getStackSize();
    }

    @Override
    default long web$getAvailable(IAEKey key, IAEGrid grid) {
        return web$extractItems(key, 1, AEActionable.SIMULATE, grid);
    }
}
