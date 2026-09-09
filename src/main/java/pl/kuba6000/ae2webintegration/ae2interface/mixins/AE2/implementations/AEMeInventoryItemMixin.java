package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPlayerSource;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IMeInventoryExtraction;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = IMEInventory.class)
public interface AEMeInventoryItemMixin extends IMeInventoryExtraction {

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    default long web$extractItems(IAEKey key, long amount, Actionable mode, IAEGrid grid) {
        if (!(key instanceof IAEStack)) {
            return 0L;
        }
        IActionSource source = ((IGridPlayerSource) grid).web$getPlayerSource();
        IAEStack<?> template = ((IAEStack<?>) (Object) key).copy();
        template.setStackSize(amount);
        IAEStack<?> extracted = (IAEStack<?>) ((IMEInventory) (Object) this).extractItems(template, mode, source);
        return extracted == null ? 0L : extracted.getStackSize();
    }

    @Override
    default long web$getAvailable(IAEKey key, IAEGrid grid) {
        return web$extractItems(key, Long.MAX_VALUE, Actionable.SIMULATE, grid);
    }
}
