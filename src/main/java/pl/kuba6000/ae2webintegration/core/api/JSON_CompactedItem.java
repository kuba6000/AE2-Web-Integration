package pl.kuba6000.ae2webintegration.core.api;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class JSON_CompactedItem {

    public final String itemid;
    public final String itemname;
    public long active = 0;
    public long pending = 0;
    public long stored = 0;
    public long timeSpentCrafting = 0;
    public long craftedTotal = 0;
    public double shareInCraftingTime = 0d;
    public double shareInCraftingTimeCombined = 0d;
    public double craftsPerSec = 0d;

    public JSON_CompactedItem(IAEKey key) {
        this.itemid = key.web$getItemID();
        this.itemname = key.web$getDisplayName();
    }

}
