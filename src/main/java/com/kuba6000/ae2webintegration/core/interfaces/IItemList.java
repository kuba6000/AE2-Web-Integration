package com.kuba6000.ae2webintegration.core.interfaces;

public interface IItemList extends Iterable<IItemStack> {

    IItemStack findPrecise(IItemStack stack);

}
