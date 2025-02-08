package com.kuba6000.ae2webintegration.core.interfaces;

public interface IItemStack {

    String getItemID();

    String getDisplayName();

    long getStackSize();

    boolean isCraftable();

    long getCountRequestable();

    long getCountRequestableCrafts();

    void reset();

    boolean isSameType(IItemStack other);

    IItemStack copy();

    void setStackSize(long size);

}
