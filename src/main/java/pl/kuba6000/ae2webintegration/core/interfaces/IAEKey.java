package pl.kuba6000.ae2webintegration.core.interfaces;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;

public interface IAEKey {

    /** Stable identity from canonical native data, independent of quantity or craftability. */
    @NotNull
    StableItemKey web$getStableKey() throws IOException;

    /** Retainable identity without mutable quantity/crafting state; native read-only identity data may be shared. */
    @NotNull
    IAEKey web$copyIdentity() throws IOException;

    @NotNull
    String web$getItemID();

    @NotNull
    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

    boolean web$isSameType(IAEKey other);

}
