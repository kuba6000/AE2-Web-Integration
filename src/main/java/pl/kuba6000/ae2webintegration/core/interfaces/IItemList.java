package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IItemList extends Iterable<IStack> {

    IStack web$findPrecise(IStack stack);

}
