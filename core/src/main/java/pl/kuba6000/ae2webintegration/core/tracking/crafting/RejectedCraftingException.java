package pl.kuba6000.ae2webintegration.core.tracking.crafting;

import java.io.IOException;

/** The detached job itself cannot be persisted; retrying it cannot repair its payload. */
final class RejectedCraftingException extends IOException {

    RejectedCraftingException(String message) {
        super(message);
    }
}
