package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IAE {

    Iterable<IAEGrid> web$getGrids();

    IItemList web$createItemList();

    IAEPlayerData web$getPlayerData();

}
