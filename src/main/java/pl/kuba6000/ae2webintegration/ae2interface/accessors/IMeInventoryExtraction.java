package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import appeng.api.config.Actionable;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;

public interface IMeInventoryExtraction extends IAEMeInventoryItem {

    long web$extractItems(IAEKey key, long amount, Actionable mode, IAEGrid grid);

}
