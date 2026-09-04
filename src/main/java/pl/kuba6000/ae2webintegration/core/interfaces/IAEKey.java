package pl.kuba6000.ae2webintegration.core.interfaces;

import java.io.IOException;

public interface IAEKey {

    /** Canonical resource identity only; unsupported native forms throw UnsupportedOperationException. */
    byte[] web$getIdentityBytes() throws IOException;

    /** Detached identity suitable for retention, without mutable quantity or craftable state. */
    IAEKey web$copyIdentity() throws IOException;

    String web$getItemID();

    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

    boolean web$isSameType(IAEKey other);

}
