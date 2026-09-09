package pl.kuba6000.ae2webintegration.core.api;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class JSON_CompactedItem {

    @GSONUtils.SkipGSON
    private final IAEKey internalKey;
    @GSONUtils.SkipGSON
    private final int hashcode;

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
        this.internalKey = key;
        this.hashcode = key.hashCode();
        this.itemid = key.web$getItemID();
        this.itemname = key.web$getDisplayName();
    }

    public static JSON_CompactedItem create(IAEKey key) {
        return new JSON_CompactedItem(key);
    }

    @Override
    public int hashCode() {
        return hashcode;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof JSON_CompactedItem) {
            return ((JSON_CompactedItem) obj).internalKey.equals(this.internalKey);
        }
        return false;
    }
}
