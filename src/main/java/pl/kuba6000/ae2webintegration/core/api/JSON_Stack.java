package pl.kuba6000.ae2webintegration.core.api;

import java.io.IOException;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Immutable output data captured on the server thread, safe for later asynchronous serialization. */
public final class JSON_Stack {

    public final String itemid;
    public final String itemname;
    public final int hashcode;
    public final long quantity;
    public final String itemKey;

    public static final JsonSerializer<JSON_Stack> SERIALIZER = (src, type, context) -> {
        JsonObject json = new JsonObject();
        json.addProperty("itemid", src.itemid);
        json.addProperty("itemname", src.itemname);
        json.addProperty("hashcode", src.hashcode);
        json.addProperty("quantity", src.quantity);
        if (src.itemKey != null) json.addProperty("itemKey", src.itemKey);
        return json;
    };

    private JSON_Stack(String itemid, String itemname, int hashcode, long quantity, String itemKey) {
        this.itemid = itemid;
        this.itemname = itemname;
        this.hashcode = hashcode;
        this.quantity = quantity;
        this.itemKey = itemKey;
    }

    public static JSON_Stack capture(IAEGenericStack stack) {
        if (stack == null) return null;
        IAEKey key = read(stack::web$what, null);
        String itemid = key == null ? null : read(key::web$getItemID, null);
        String itemname = key == null ? null : read(key::web$getDisplayName, null);
        int hashcode = read(stack::hashCode, 0);
        long quantity = read(stack::web$amount, 0L);
        try {
            String itemKey = key == null ? null
                : AE2Controller.itemIdentities.remember(key)
                    .toString();
            return new JSON_Stack(itemid, itemname, hashcode, quantity, itemKey);
        } catch (IOException | RuntimeException exception) {
            return new JSON_Stack(itemid, itemname, hashcode, quantity, null);
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
