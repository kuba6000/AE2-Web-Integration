package com.kuba6000.ae2webintegration.api;

import appeng.api.storage.data.IAEItemStack;

public class JSON_Item {

    public int hashcode;
    public String itemid;
    public String itemname;
    public long quantity;

    public JSON_Item(String itemid, String itemname, long quantity, int hashcode) {
        this.itemid = itemid;
        this.itemname = itemname;
        this.quantity = quantity;
        this.hashcode = hashcode;
    }

    public static JSON_Item create(IAEItemStack src) {
        return new JSON_Item(
            src.getItem().getRegistryName() + ":"
                + src.getItemDamage(),
            src.asItemStackRepresentation()
                .getDisplayName(),
            src.getStackSize(),
            src.hashCode());
    }
}
