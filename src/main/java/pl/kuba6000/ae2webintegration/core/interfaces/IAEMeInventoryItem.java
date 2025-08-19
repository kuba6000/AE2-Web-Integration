package pl.kuba6000.ae2webintegration.core.interfaces;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;

public interface IAEMeInventoryItem {

    IItemStack web$extractItems(IItemStack stack, AEActionable mode, IAEGrid grid);

    IItemStack web$getAvailableItem(IItemStack stack);

}
