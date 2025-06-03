package com.kuba6000.ae2webintegration.core.api;

import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;
import com.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class JSON_CompactedItem {

    @GSONUtils.SkipGSON
    private final IItemStack internalItem;
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

    public JSON_CompactedItem(IItemStack itemStack) {
        this.internalItem = itemStack;
        this.hashcode = this.internalItem.hashCode();
        this.itemid = itemStack.web$getItemID();
        this.itemname = itemStack.web$getDisplayName();
    }

    public static JSON_CompactedItem create(IItemStack stack) {
        return new JSON_CompactedItem(stack);
    }

    @Override
    public int hashCode() {
        return hashcode;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof JSON_CompactedItem) {
            return ((JSON_CompactedItem) obj).internalItem.equals(this.internalItem);
        }
        return false;
    }
}
