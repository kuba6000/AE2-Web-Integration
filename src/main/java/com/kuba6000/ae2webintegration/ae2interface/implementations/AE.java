package com.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.Iterator;

import net.minecraftforge.server.ServerLifecycleHooks;

import com.kuba6000.ae2webintegration.core.interfaces.IAE;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import com.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import com.kuba6000.ae2webintegration.core.interfaces.IItemList;

import appeng.api.features.IPlayerRegistry;
import appeng.api.stacks.KeyCounter;
import appeng.hooks.ticking.TickHandler;
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

                private final Iterator<Grid> iterator = TickHandler.instance()
                    .getGridList()
                    .iterator();

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public IAEGrid next() {
                    return (IAEGrid) iterator.next();
                }
            };
        }
    }

    @Override
    public Iterable<IAEGrid> web$getGrids() {
        return new AEGridIterable();
    }

    @Override
    public IItemList web$createItemList() {
        return (IItemList) (Object) new KeyCounter();
    }

    @Override
    public IAEPlayerData web$getPlayerData() {
        return (IAEPlayerData) IPlayerRegistry.getMapping(ServerLifecycleHooks.getCurrentServer());
    }
}
