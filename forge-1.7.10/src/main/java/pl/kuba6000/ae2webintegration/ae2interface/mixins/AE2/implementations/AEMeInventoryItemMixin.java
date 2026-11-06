package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IMeInventoryExtraction;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IMeInventoryExtraction {

    @Override
    @SuppressWarnings("unchecked")
    default long web$extractItems(IAEKey key, long amount, Actionable mode, IAEGrid grid) {
        IAEStack<?> template = ((IAEStack<?>) (Object) key).copy();
        template.setStackSize(amount);
        IAEStack<?> extracted = ((IMEInventory) (Object) this)
            .extractItems(template, mode, ((IGridPlayerSource) grid).web$getPlayerSource());
        return extracted == null ? 0 : extracted.getStackSize();
    }

    @Override
    @SuppressWarnings("unchecked")
    default long web$getAvailable(IAEKey key, IAEGrid grid) {
        IAEStack<?> found = (IAEStack<?>) ((IMEInventory) (Object) this).getAvailableItem((IAEStack<?>) (Object) key);
        return found == null ? 0 : found.getStackSize();
    }
}
