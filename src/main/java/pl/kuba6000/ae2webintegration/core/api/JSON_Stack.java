package pl.kuba6000.ae2webintegration.core.api;

import java.io.IOException;
import java.util.function.Supplier;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Immutable output data captured on the server thread, safe for later asynchronous serialization. */
public final class JSON_Stack {

    public final String itemid;
    public final String itemname;
    public final long quantity;
    public final String itemKey;

    private JSON_Stack(String itemid, String itemname, long quantity, String itemKey) {
        this.itemid = itemid;
        this.itemname = itemname;
        this.quantity = quantity;
        this.itemKey = itemKey;
    }

    public static JSON_Stack capture(IAEGenericStack stack) {
        if (stack == null) return null;
        IAEKey key = read(stack::web$what, null);
        String itemid = key == null ? null : read(key::web$getItemID, null);
        String itemname = key == null ? null : read(key::web$getDisplayName, null);
        long quantity = read(stack::web$amount, 0L);
        try {
            String itemKey = key == null ? null
                : AE2Controller.itemIdentities.remember(key)
                    .toString();
            return new JSON_Stack(itemid, itemname, quantity, itemKey);
        } catch (IOException | RuntimeException exception) {
            return new JSON_Stack(itemid, itemname, quantity, null);
        }
    }

    private static <T> T read(Supplier<T> nativeValue, T fallback) {
        try {
            return nativeValue.get();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
