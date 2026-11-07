package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import org.spongepowered.asm.mixin.Mixin;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IGridPlayerSource;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;

@Mixin(value = MEStorage.class)
public interface AEMeInventoryItemMixin extends IAEMeInventoryItem {

    @Override
    default long web$getAvailable(IAEKey key, IAEGrid grid) {
        return ((MEStorage) (Object) this).extract(
            (AEKey) key,
            Long.MAX_VALUE,
            Actionable.SIMULATE,
            ((IGridPlayerSource) grid).web$getPlayerSource());
    }
}
