package com.kuba6000.ae2webintegration.api;

import appeng.api.storage.data.IAEItemStack;
import cpw.mods.fml.common.registry.GameRegistry;

public class JSON_Item {

    public String itemid;
    public String itemname;
    public long quantity;

    public JSON_Item(String itemid, String itemname, long quantity) {
        this.itemid = itemid;
        this.itemname = itemname;
        this.quantity = quantity;
    }

    public static JSON_Item create(IAEItemStack src) {
        return new JSON_Item(
            GameRegistry.findUniqueIdentifierFor(src.getItem())
                .toString() + ":"
                + src.getItemDamage(),
            src.getItemStack()
                .getDisplayName(),
            src.getStackSize());
    }
}
