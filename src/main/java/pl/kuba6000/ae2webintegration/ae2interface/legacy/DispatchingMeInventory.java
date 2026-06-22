package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;

public final class DispatchingMeInventory implements IAEMeInventoryItem {

    private final IAEMeInventoryItem items;
    private final IAEMeInventoryItem fluids;

    public DispatchingMeInventory(IAEMeInventoryItem items, IAEMeInventoryItem fluids) {
        this.items = items;
        this.fluids = fluids;
    }

    @Override
    public long web$extractItems(IAEKey key, long amount, AEActionable mode, IAEGrid grid) {
        return channel(key).web$extractItems(key, amount, mode, grid);
    }

    @Override
    public long web$getAvailable(IAEKey key, IAEGrid grid) {
        return channel(key).web$getAvailable(key, grid);
    }

    private IAEMeInventoryItem channel(IAEKey key) {
        return key.web$isFluid() ? fluids : items;
    }
}
