package pl.kuba6000.ae2webintegration.core.interfaces;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;

public interface IAEMeInventoryItem {

    long web$extractItems(IAEKey key, long amount, AEActionable mode, IAEGrid grid);

    long web$getAvailable(IAEKey key, IAEGrid grid);

}
