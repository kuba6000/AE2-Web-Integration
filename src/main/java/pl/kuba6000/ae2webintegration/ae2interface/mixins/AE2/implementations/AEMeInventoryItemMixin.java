package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    @SuppressWarnings("unchecked")
    default long web$extractItems(IAEKey key, long amount, AEActionable mode, IAEGrid grid) {
        IAEStack<?> template = ((IAEStack<?>) (Object) key).copy();
        template.setStackSize(amount);
        IAEStack<?> extracted = ((IMEInventory) (Object) this).extractItems(
            template,
            mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE,
            (BaseActionSource) grid.web$getPlayerSource());
        return extracted == null ? 0 : extracted.getStackSize();
    }

    @Override
    @SuppressWarnings("unchecked")
    default long web$getAvailable(IAEKey key, IAEGrid grid) {
        IAEStack<?> found = (IAEStack<?>) ((IMEInventory) (Object) this).getAvailableItem((IAEStack<?>) (Object) key);
        return found == null ? 0 : found.getStackSize();
    }
}
