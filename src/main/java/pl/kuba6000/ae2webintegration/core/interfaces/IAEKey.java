package pl.kuba6000.ae2webintegration.core.interfaces;

public interface IAEKey {

    String web$getItemID();

    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

    boolean web$isSameType(IAEKey other);

    /** @return true for fluid stacks on legacy AE2 versions with split channels */
    default boolean web$isFluid() {
        return false;
    }

}
