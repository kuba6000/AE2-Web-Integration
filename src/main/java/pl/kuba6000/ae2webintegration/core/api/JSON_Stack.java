package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Immutable output data captured on the server thread, safe for later asynchronous serialization. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public final class JSON_Stack {

    /**
     * Registry resource identifier.
     *
     * @example minecraft:iron_ingot
     */
    public final @NotNull String itemid;
    /**
     * Resource display name.
     *
     * @example Iron Ingot
     */
    public final @NotNull String itemname;
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

    private JSON_Stack(@NotNull String itemid, @NotNull String itemname, long quantity, @Nullable String itemKey) {
        this.itemid = itemid;
        this.itemname = itemname;
        this.quantity = quantity;
        this.itemKey = itemKey;
    }

    public static @Nullable JSON_Stack capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack) {
        if (stack == null) return null;
        IAEKey key = stack.web$what();
        String itemid = key.web$getItemID();
        String itemname = key.web$getDisplayName();
        long quantity = stack.web$amount();
        try {
            String itemKey = AE2Controller.itemIdentities.remember(grid, key)
                .toString();
            return new JSON_Stack(itemid, itemname, quantity, itemKey);
        } catch (RuntimeException exception) {
            return new JSON_Stack(itemid, itemname, quantity, null);
        }
    }

}
