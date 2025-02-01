package com.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.Iterator;

import com.kuba6000.ae2webintegration.core.interfaces.IAE;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;

import appeng.api.AEApi;
import appeng.hooks.TickHandler;
import appeng.me.Grid;

public class AE implements IAE {

    public static AE instance = new AE();

    public static AE getInstance() {
        return instance;
    }

    static class AEGridIterable implements Iterable<IAEGrid> {

        @Override
        public java.util.Iterator<IAEGrid> iterator() {
            return new java.util.Iterator<>() {

                private final Iterator<Grid> iterator = TickHandler.INSTANCE.getGridList()
                    .iterator();

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public IAEGrid next() {
                    return new AEGrid(iterator.next());
                }
            };
        }
    }

    @Override
    public Iterable<IAEGrid> getGrids() {
        return new AEGridIterable();
    }

    @Override
    public IItemList createItemList() {
        return new ItemList(
            AEApi.instance()
                .storage()
                .createItemList());
    }

}
