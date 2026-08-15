package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    default long web$extractItems(IAEKey key, long amount, AEActionable mode, IAEGrid grid) {
        if (!(key instanceof IAEStack)) {
            return 0L;
        }
        Actionable actionable = mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE;
        IActionSource source = (IActionSource) grid.web$getPlayerSource();
        IAEStack<?> template = ((IAEStack<?>) (Object) key).copy();
        template.setStackSize(amount);
        IAEStack<?> extracted = (IAEStack<?>) ((IMEInventory) (Object) this).extractItems(template, actionable, source);
        return extracted == null ? 0L : extracted.getStackSize();
    }

    @Override
    default long web$getAvailable(IAEKey key, IAEGrid grid) {
        return web$extractItems(key, Long.MAX_VALUE, AEActionable.SIMULATE, grid);
    }
}
