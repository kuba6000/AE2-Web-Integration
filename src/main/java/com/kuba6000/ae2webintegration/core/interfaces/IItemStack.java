package com.kuba6000.ae2webintegration.core.interfaces;

public interface IItemStack {

    String web$getItemID();

    String web$getDisplayName();

    long web$getStackSize();

    boolean web$isCraftable();

    long web$getCountRequestable();

    long web$getCountRequestableCrafts();

    void web$reset();

    boolean web$isSameType(IItemStack other);

    IItemStack web$copy();

    void web$setStackSize(long size);

}
