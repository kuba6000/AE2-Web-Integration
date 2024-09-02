package com.kuba6000.ae2webintegration.api;

public class JSON_Item {

    public String itemid;
    public String itemname;
    public long quantity;

    public JSON_Item(String itemid, String itemname, long quantity) {
        this.itemid = itemid;
        this.itemname = itemname;
        this.quantity = quantity;
    }
}
