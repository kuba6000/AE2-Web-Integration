package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IAE {

    Iterable<IAEGrid> web$getGrids();

    IStackList web$createStackList();

    IAEGenericStack web$stackOf(IAEKey key, long amount);

    IAEPlayerData web$getPlayerData();

}
