package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Overrides the synthetic native actor only during a synchronous web submission. */
public final class CraftingRequesterContext implements AutoCloseable {

    private static final ThreadLocal<CraftingRequesterContext> CURRENT = new ThreadLocal<>();
    private final @Nullable CraftingRequesterContext previous;
    private final @NotNull CraftingRequester requester;

    private CraftingRequesterContext(@NotNull CraftingRequester requester) {
        previous = CURRENT.get();
        this.requester = requester;
        CURRENT.set(this);
    }

    /** Attributes the submission to a player, or to the web when the administrator has no Minecraft identity. */
    public static @NotNull CraftingRequesterContext open(@Nullable PlayerIdentity requester) {
        // A web administrator has no Minecraft identity; null must still override the fake native actor.
        return new CraftingRequesterContext(
            requester == null ? CraftingRequester.web() : CraftingRequester.player(requester));
    }

    /** Attributes the submission to a standing order. */
    public static @NotNull CraftingRequesterContext openStandingOrder(@NotNull StableKey orderKey,
        @NotNull String orderName) {
        return new CraftingRequesterContext(CraftingRequester.standingOrder(orderKey, orderName));
    }

    public static @NotNull CraftingRequester resolve(@Nullable PlayerIdentity nativeRequester) {
        CraftingRequesterContext current = CURRENT.get();
        if (current != null) return current.requester;
        return nativeRequester == null ? CraftingRequester.automation() : CraftingRequester.player(nativeRequester);
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
