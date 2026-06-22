package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.Iterator;

import appeng.api.AEApi;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.core.worlddata.WorldData;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

public class AE implements IAE {

    public static AE instance = new AE();

    public static AE getInstance() {
        return instance;
    }

    static class AEGridIterable implements Iterable<IAEGrid> {

        @Override
        public Iterator<IAEGrid> iterator() {
            return new Iterator<>() {

                private final Iterator<Grid> iterator = TickHandler.INSTANCE.getGridList()
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
    public IStackList web$createStackList() {
        return (IStackList) (Object) AEApi.instance()
            .storage()
            .getStorageChannel(IItemStorageChannel.class)
            .createList();
    }

    @Override
    public IAEGenericStack web$stackOf(IAEKey key, long amount) {
        if (key.web$isFluid()) {
            IAEFluidStack stack = ((IAEFluidStack) (Object) key).copy();
            stack.setStackSize(amount);
            return (IAEGenericStack) stack;
        }
        IAEItemStack stack = ((IAEItemStack) (Object) key).copy();
        stack.setStackSize(amount);
        return (IAEGenericStack) stack;
    }

    @Override
    public IAEPlayerData web$getPlayerData() {
        return (IAEPlayerData) WorldData.instance()
            .playerData();
    }
}
