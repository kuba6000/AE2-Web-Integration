package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IAEKey {

    String web$getItemID();

    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

    boolean web$isSameType(IAEKey other);

}
