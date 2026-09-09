package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IStackList {

    long web$getAmount(IAEKey key);

    Iterable<IAEGenericStack> web$stacks();

}
