package pl.kuba6000.ae2webintegration.core.http;

import java.util.Map;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;

/** Request-local parsed transport inputs consumed by generated bindings. */
public final class InputValues {

    private final RequestContext context;
    private final String route;
    private @Nullable Map<String, String> query;

    public InputValues(RequestContext context, String route) {
        this.context = context;
        this.route = route;
    }

    public @Nullable String query(String name) {
        if (query == null) query = HTTPUtils.parseQueryString(
            context.getExchange()
                .getRequestURI()
                .getRawQuery());
        return query.get(name);
    }

    public boolean hasPath(String name) {
        return route.contains("{" + name + "}");
    }

    public String path(String name) {
        String value = context.getPathParams()
            .get(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing path parameter: " + name);
        return value;
    }

    public static boolean booleanValue(String value) {
        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException("Expected boolean query parameter");
        return value.equals("true");
    }

    public void noBody() {
        JsonObject body = context.getBody();
        if (body != null && !body.entrySet()
            .isEmpty()) throw new IllegalArgumentException("This endpoint has no request body");
    }

    public <T> T body(Class<T> type, Consumer<JsonObject> validate) {
        JsonObject body = context.getBody();
        if (body == null) throw new IllegalArgumentException("Missing JSON body");
        validate.accept(body);
        return GSONUtils.GSON_BUILDER.create()
            .fromJson(body, type);
    }

    public static JsonObject object(@Nullable JsonElement value, String name) {
        if (value == null || !value.isJsonObject())
            throw new IllegalArgumentException("Missing or invalid JSON object: " + name);
        return value.getAsJsonObject();
    }

    private static JsonPrimitive scalar(@Nullable JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive())
            throw new IllegalArgumentException("Missing or invalid JSON member: " + name);
        return value.getAsJsonPrimitive();
    }

    public static void string(@Nullable JsonElement value, String name) {
        if (!scalar(value, name).isString()) throw new IllegalArgumentException("Expected string: " + name);
    }

    public static void key(@Nullable JsonElement value, String name) {
        JsonPrimitive scalar = scalar(value, name);
        if (!scalar.isString()) throw new IllegalArgumentException("Expected string: " + name);
        StableKey.parse(scalar.getAsString());
    }

    public static void bool(@Nullable JsonElement value, String name) {
        if (!scalar(value, name).isBoolean()) throw new IllegalArgumentException("Expected boolean: " + name);
    }

    public static void integer(@Nullable JsonElement value, String name, boolean narrow) {
        JsonPrimitive scalar = scalar(value, name);
        if (!scalar.isNumber()) throw new IllegalArgumentException("Expected integer: " + name);
        try {
            if (narrow) scalar.getAsBigDecimal()
                .intValueExact();
            else scalar.getAsBigDecimal()
                .longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid integer", exception);
        }
    }
}
