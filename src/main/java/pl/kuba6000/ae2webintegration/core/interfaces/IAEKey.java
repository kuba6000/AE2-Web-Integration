package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Native resource key: equals/hashCode identify the exact variant independently of quantity or crafting state. */
public interface IAEKey {

    /** Stable identity from canonical native data, independent of quantity or craftability. */
    @NotNull
    StableKey web$getKey();

    /** Preserves resource identity for retention; quantity/crafting state may be reset and read-only data shared. */
    @NotNull
    IAEKey web$copyIdentity();

    @NotNull
    String web$getItemID();

    @NotNull
    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

    boolean web$isSameType(IAEKey other);

}
