package pl.kuba6000.ae2webintegration.core.interfaces;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;

public interface IAEMeInventoryItem {

    IStack web$extractItems(IStack stack, AEActionable mode, IAEGrid grid);

    long web$extractItems(IAEKey stack, long amount, AEActionable mode, IAEGrid grid);

    IStack web$getAvailableItem(IStack stack);

    long web$getAvailableItem(IAEKey stack, IAEGrid grid);

}
