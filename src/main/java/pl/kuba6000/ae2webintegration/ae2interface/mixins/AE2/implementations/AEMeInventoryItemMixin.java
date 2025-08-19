package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;

@Mixin(value = MEStorage.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    public default long web$extractItems(IAEKey stack, long amount, AEActionable mode, IAEGrid grid) {
        return ((MEStorage) (Object) this).extract(
            (AEKey) stack,
            amount,
            mode == AEActionable.MODULATE ? Actionable.MODULATE : Actionable.SIMULATE,
            (IActionSource) grid.web$getPlayerSource());
    }

    @Override
    public default long web$getAvailableItem(IAEKey stack, IAEGrid grid) {
        return this.web$extractItems(stack, Long.MAX_VALUE, AEActionable.SIMULATE, grid);
    }
}
