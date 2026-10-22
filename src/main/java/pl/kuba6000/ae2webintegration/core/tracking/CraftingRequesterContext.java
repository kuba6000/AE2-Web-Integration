package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

/** Overrides the synthetic native actor only during a synchronous web submission. */
public final class CraftingRequesterContext implements AutoCloseable {

    private static final ThreadLocal<CraftingRequesterContext> CURRENT = new ThreadLocal<>();
    private final @Nullable CraftingRequesterContext previous;
    private final @Nullable PlayerIdentity requester;

    private CraftingRequesterContext(@Nullable PlayerIdentity requester) {
        previous = CURRENT.get();
        this.requester = requester;
        CURRENT.set(this);
    }

    public static @NotNull CraftingRequesterContext open(@Nullable PlayerIdentity requester) {
        return new CraftingRequesterContext(requester);
    }

    public static @Nullable PlayerIdentity resolve(@Nullable PlayerIdentity nativeRequester) {
        CraftingRequesterContext current = CURRENT.get();
        // A web administrator has no Minecraft identity; null must still override the fake native actor.
        return current == null ? nativeRequester : current.requester;
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
