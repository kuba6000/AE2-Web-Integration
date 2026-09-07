package pl.kuba6000.ae2webintegration.core.interfaces;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;

/** Native resource key: equals/hashCode identify the exact variant independently of quantity or crafting state. */
public interface IAEKey {

    /** Stable identity from canonical native data, independent of quantity or craftability. */
    @NotNull
    StableItemKey web$getStableKey() throws IOException;

    /** Preserves resource identity for retention; quantity/crafting state may be reset and read-only data shared. */
    @NotNull
    IAEKey web$copyIdentity() throws IOException;

    @NotNull
    String web$getItemID();

    @NotNull
    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

    boolean web$isSameType(IAEKey other);

}
