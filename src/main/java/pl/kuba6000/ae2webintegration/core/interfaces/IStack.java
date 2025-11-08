package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IStack {

    String web$getItemID();

    String web$getDisplayName();

    long web$getStackSize();

    boolean web$isCraftable();

    long web$getCountRequestable();

    long web$getCountRequestableCrafts();

    void web$reset();

    boolean web$isSameType(IStack other);

    IStack web$copy();

    void web$setStackSize(long size);

}
