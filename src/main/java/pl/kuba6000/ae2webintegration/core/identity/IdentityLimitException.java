package pl.kuba6000.ae2webintegration.core.identity;

import java.io.IOException;

/** Identity encoding exceeded its work/payload budget; no partial token may be used. */
public final class IdentityLimitException extends IOException {

    private static final long serialVersionUID = 1L;

    public IdentityLimitException() {
        super("Canonical identity exceeds the byte budget");
    }
}
