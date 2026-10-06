package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Immutable output data captured on the server thread, safe for later asynchronous serialization. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public final class ResourceStack {

    /**
     * Registry resource identifier.
     *
     * @example minecraft:iron_ingot
     */
    public final @NotNull String itemId;
    /**
     * Resource display name.
     *
     * @example Iron Ingot
     */
    public final @NotNull String itemName;
    /**
     * Number of resource units.
     *
     * @example 64
     */
    public final long quantity;
    /**
     * Stable resource key, or null when an identity could not be captured.
     *
     * @example AAAAAAAAAAAAAAAAAAAAAA
     */
    public final @Nullable String itemKey;
    /** Detached fallback identity for tracked history; captured only when the installed pack lacks the exact icon. */
    public final transient @Nullable StableKey iconBaseKey;

    private ResourceStack(@NotNull String itemId, @NotNull String itemName, long quantity, @Nullable String itemKey,
        @Nullable StableKey iconBaseKey) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.quantity = quantity;
        this.itemKey = itemKey;
        this.iconBaseKey = iconBaseKey;
    }

    public static @Nullable ResourceStack capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack) {
        return capture(grid, stack, null);
    }

    /** Captures history's optional fallback on the server thread before native identity ownership can expire. */
    public static @Nullable ResourceStack capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack,
        @Nullable IconPack pack) {
        if (stack == null) return null;
        IAEKey key = stack.web$what();
        String itemId = key.web$getItemID();
        String itemName = key.web$getDisplayName();
        long quantity = stack.web$amount();
        StableKey itemKey;
        try {
            itemKey = AE2Controller.itemIdentities.remember(grid, key);
        } catch (RuntimeException exception) {
            return new ResourceStack(itemId, itemName, quantity, null, null);
        }
        StableKey base = null;
        if (pack != null && pack.find(itemKey) == null) {
            try {
                base = AE2Controller.itemIdentities.resolveIconBase(itemKey);
            } catch (RuntimeException ignored) {
                // Optional fallback must not discard a successfully captured exact identity.
            }
        }
        return new ResourceStack(itemId, itemName, quantity, itemKey.toString(), base);
    }

}
