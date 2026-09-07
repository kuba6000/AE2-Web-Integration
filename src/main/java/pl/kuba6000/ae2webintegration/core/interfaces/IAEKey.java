package pl.kuba6000.ae2webintegration.core.interfaces;

import java.io.IOException;

import org.jetbrains.annotations.NotNull;

public interface IAEKey {

    /** Canonical resource identity only; unsupported native forms throw UnsupportedOperationException. */
    byte @NotNull [] web$getIdentityBytes() throws IOException;

    /** Detached identity suitable for retention, without mutable quantity or craftable state. */
    @NotNull
    IAEKey web$copyIdentity() throws IOException;

    @NotNull
    String web$getItemID();

    @NotNull
    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

    boolean web$isSameType(IAEKey other);

}
