package com.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.Iterator;

import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;

public class ItemList extends IAEStrongObject<IItemList<IAEItemStack>>
    implements com.kuba6000.ae2webintegration.core.interfaces.IItemList {

    public ItemList(IItemList<IAEItemStack> object) {
        super(object);
    }

    @Override
    public Iterator<IItemStack> iterator() {
        final Iterator<IAEItemStack> iterator = get().iterator();
        return new Iterator<>() {

            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public IItemStack next() {
                return (IItemStack) iterator.next();
            }
        };
    }

    @Override
    public IItemStack findPrecise(IItemStack stack) {
        return (IItemStack) get().findPrecise((IAEItemStack) stack);
    }
}
