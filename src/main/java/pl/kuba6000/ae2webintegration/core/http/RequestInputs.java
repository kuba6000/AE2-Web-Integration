package pl.kuba6000.ae2webintegration.core.http;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** Binds only the fields declared by the endpoint; JSON strings are never coerced into numbers. */
public final class RequestInputs {

    private RequestInputs() {}

    public static void bind(Object request, RequestContext context) {
        Endpoint endpoint = request.getClass()
            .getAnnotation(Endpoint.class);
        if (endpoint == null) return; // Internal tasks are not HTTP endpoints.
        boolean hasBody = false;
        for (Class<?> type = request.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                PathParam path = field.getAnnotation(PathParam.class);
                if (path != null && endpoint.path()
                    .contains("{" + path.value() + "}")) {
                    String value = context.getPathParams()
                        .get(path.value());
                    if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing path parameter");
                    set(field, request, parsePath(value, field.getType()));
                }
                if (field.isAnnotationPresent(Body.class)) {
                    if (hasBody) throw new IllegalStateException("Only one body field is allowed");
                    hasBody = true;
                    JsonObject body = context.getBody();
                    if (body == null) throw new IllegalArgumentException("Missing JSON body");
                    validate(body, field.getType());
                    set(
                        field,
                        request,
                        GSONUtils.GSON_BUILDER.create()
                            .fromJson(body, field.getType()));
                }
            }
        }
        if (!hasBody && context.getBody() != null
            && context.getBody()
                .size() != 0) {
            throw new IllegalArgumentException("This endpoint has no request body");
        }
    }

    private static Object parsePath(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == StableKey.class) return StableKey.parse(value);
        if (type == int.class || type == Integer.class) return Integer.valueOf(value);
        if (type == long.class || type == Long.class) return Long.valueOf(value);
        throw new IllegalStateException("Unsupported path type: " + type);
    }

    private static void set(Field field, Object target, Object value) {
        if (Modifier.isStatic(field.getModifiers()))
            throw new IllegalStateException("Request fields must not be static");
        field.setAccessible(true);
        try {
            field.set(target, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void validate(JsonObject body, Class<?> input) {
        Map<String, Field> fields = new HashMap<>();
        for (Field field : input.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers()))
                fields.put(field.getName(), field);
        }
        for (String supplied : body.keySet()) {
            if (!fields.containsKey(supplied)) throw new IllegalArgumentException("Unknown JSON member: " + supplied);
        }
        for (Field field : fields.values()) {
            JsonElement value = body.get(field.getName());
            if (value == null && field.isAnnotationPresent(OptionalInput.class)) continue;
            if (value == null || !value.isJsonPrimitive())
                throw new IllegalArgumentException("Missing or invalid JSON member: " + field.getName());
            JsonPrimitive scalar = value.getAsJsonPrimitive();
            Class<?> type = field.getType();
            if (type == String.class || type == StableKey.class) {
                if (!scalar.isString()) throw new IllegalArgumentException("Expected string: " + field.getName());
                if (type == StableKey.class) StableKey.parse(scalar.getAsString());
            } else if (type == boolean.class || type == Boolean.class) {
                if (!scalar.isBoolean()) throw new IllegalArgumentException("Expected boolean: " + field.getName());
            } else if (type == long.class || type == Long.class || type == int.class || type == Integer.class) {
                if (!scalar.isNumber()) throw new IllegalArgumentException("Expected integer: " + field.getName());
                try {
                    if (type == int.class || type == Integer.class) scalar.getAsBigDecimal()
                        .intValueExact();
                    else scalar.getAsBigDecimal()
                        .longValueExact();
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException("Invalid integer", exception);
                }
            } else throw new IllegalStateException("Unsupported input type: " + type);
        }
    }
}
