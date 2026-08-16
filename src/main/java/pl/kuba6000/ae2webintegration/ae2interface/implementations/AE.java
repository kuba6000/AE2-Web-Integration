package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.Iterator;
import java.util.WeakHashMap;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import appeng.api.features.IPlayerRegistry;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.hooks.ticking.TickHandler;
import appeng.me.Grid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

public class AE implements IAE {

    public static WeakHashMap<ICraftingCPUCluster, Integer> cpuInternalIDMap = new WeakHashMap<>();

    public static AE instance = new AE();

    public static AE getInstance() {
        return instance;
    }

    static class AEGridIterable implements Iterable<IAEGrid> {

        @Override
        public Iterator<IAEGrid> iterator() {
            return new Iterator<>() {

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
    public IStackList web$createStackList() {
        return (IStackList) (Object) new KeyCounter();
    }

    @Override
    public IAEGenericStack web$stackOf(IAEKey key, long amount) {
        return (IAEGenericStack) (Object) new GenericStack((AEKey) key, amount);
    }

    @Override
    public IAEPlayerData web$getPlayerData() {
        return (IAEPlayerData) IPlayerRegistry.getMapping(ServerLifecycleHooks.getCurrentServer());
    }
}
