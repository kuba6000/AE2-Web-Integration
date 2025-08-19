package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IItemList extends Iterable<IItemStack> {

    IItemStack web$findPrecise(IItemStack stack);

}
