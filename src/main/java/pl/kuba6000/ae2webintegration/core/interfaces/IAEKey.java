package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IAEKey {

    String web$getItemID();

    String web$getDisplayName();

    // long web$getStackSize();

    boolean web$isCraftable(IAEGrid grid);

    // long web$getCountRequestable();

    // long web$getCountRequestableCrafts();

    // void web$reset();

    boolean web$isSameType(IAEKey other);

    // IAEKey web$copy();

    // void web$setStackSize(long size);

}
