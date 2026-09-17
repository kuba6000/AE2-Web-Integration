package pl.kuba6000.ae2webintegration.doclet;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Builds documentation samples from the supported schemas without loading application classes. */
final class OpenApiExamples {

    private OpenApiExamples() {}

    static void addHint(JsonObject schema, String tag, String text) {
        JsonObject target = schema;
        if (tag.equals("keyExample")) {
            target = nonNullSchema(schema).getAsJsonObject("propertyNames");
            if (target == null) throw new IllegalArgumentException("@keyExample requires a map");
        }
        if (target.has("examples")) throw new IllegalArgumentException("Duplicate @" + tag);
        JsonArray examples = new JsonArray();
        examples.add(scalar(text, target));
        target.add("examples", examples);
    }

    private static JsonElement scalar(String text, JsonObject schema) {
        if (text.isEmpty()) throw new IllegalArgumentException("Missing example value");
        if (text.equals("null") && (schema.has("anyOf") || "null".equals(type(schema)))) return JsonNull.INSTANCE;
        JsonObject expected = nonNullSchema(schema);
        JsonElement value = switch (type(expected)) {
            case "string" -> text.startsWith("\"") ? JsonParser.parseString(text) : new JsonPrimitive(text);
            case "boolean" -> {
                if (!text.equals("true") && !text.equals("false"))
                    throw new IllegalArgumentException("Expected boolean example");
                yield new JsonPrimitive(Boolean.parseBoolean(text));
            }
            case "integer", "number" -> new JsonPrimitive(new BigDecimal(text));
            default -> throw new IllegalArgumentException(
                "@example requires a scalar value; use @responseExample for complete JSON");
        };
        if (!matchesScalar(value, expected)) throw new IllegalArgumentException("Example does not match " + expected);
        return value;
    }

    private static boolean matchesScalar(JsonElement value, JsonObject schema) {
        if (!value.isJsonPrimitive()) return false;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (schema.has("enum") && !schema.getAsJsonArray("enum")
            .contains(value)) return false;
        return switch (type(schema)) {
            case "string" -> primitive.isString() && (!schema.has("minLength") || value.getAsString()
                .length()
                >= schema.get("minLength")
                    .getAsInt())
                && (!schema.has("maxLength") || value.getAsString()
                    .length()
                    <= schema.get("maxLength")
                        .getAsInt())
                && (!schema.has("format") || !schema.get("format")
                    .getAsString()
                    .equals("uuid")
                    || UUID.fromString(value.getAsString())
                        .toString()
                        .equalsIgnoreCase(value.getAsString()));
            case "boolean" -> primitive.isBoolean();
            case "integer" -> primitive.isNumber() && primitive.getAsBigDecimal()
                .stripTrailingZeros()
                .scale() <= 0;
            case "number" -> primitive.isNumber();
            default -> false;
        };
    }

    static void completeExamples(JsonObject document) {
        JsonObject definitions = document.getAsJsonObject("components")
            .getAsJsonObject("schemas");
        for (JsonElement path : document.getAsJsonObject("paths")
            .asMap()
            .values()) {
            for (JsonElement operation : path.getAsJsonObject()
                .asMap()
                .values()) {
                JsonObject request = operation.getAsJsonObject()
                    .getAsJsonObject("requestBody");
                if (request != null) completeContent(request, definitions, "Request");
                for (JsonElement response : operation.getAsJsonObject()
                    .getAsJsonObject("responses")
                    .asMap()
                    .values()) {
                    completeContent(response.getAsJsonObject(), definitions, "Response");
                }
            }
        }
    }

    private static void completeContent(JsonObject message, JsonObject definitions, String direction) {
        JsonObject content = message.getAsJsonObject("content");
        if (content == null) return;
        JsonObject media = content.getAsJsonObject("application/json");
        JsonObject schema = media.getAsJsonObject("schema");
        JsonElement example = media.has("example") ? media.get("example")
            : generate(schema, definitions, new HashSet<>());
        // A required, non-null recursive object has no finite sample. Its schema remains documented.
        if (example != null) {
            if (!matches(example, schema, definitions)) {
                throw new IllegalArgumentException(direction + " example does not match its schema: " + schema);
            }
            media.add("example", example);
        }
    }

    private static boolean matches(JsonElement value, JsonObject schema, JsonObject definitions) {
        if (schema.has("$ref")) {
            String reference = schema.get("$ref")
                .getAsString();
            return matches(
                value,
                definitions.getAsJsonObject(reference.substring(reference.lastIndexOf('/') + 1)),
                definitions);
        }
        if (schema.has("anyOf")) {
            for (JsonElement alternative : schema.getAsJsonArray("anyOf")) {
                if (matches(value, alternative.getAsJsonObject(), definitions)) return true;
            }
            return false;
        }
        switch (type(schema)) {
            case "null":
                return value.isJsonNull();
            case "array":
                if (!value.isJsonArray()) return false;
                for (JsonElement item : value.getAsJsonArray()) {
                    if (!matches(item, schema.getAsJsonObject("items"), definitions)) return false;
                }
                return true;
            case "object":
                if (!value.isJsonObject()) return false;
                JsonObject object = value.getAsJsonObject();
                if (schema.has("required")) {
                    for (JsonElement required : schema.getAsJsonArray("required")) {
                        if (!object.has(required.getAsString())) return false;
                    }
                }
                JsonObject properties = schema.getAsJsonObject("properties");
                for (Map.Entry<String, JsonElement> member : object.entrySet()) {
                    if (schema.has("propertyNames") && !matches(
                        new JsonPrimitive(member.getKey()),
                        schema.getAsJsonObject("propertyNames"),
                        definitions)) return false;
                    JsonElement additional = schema.get("additionalProperties");
                    boolean knownProperty = properties != null && properties.has(member.getKey());
                    if (!knownProperty && additional != null
                        && additional.isJsonPrimitive()
                        && !additional.getAsBoolean()) {
                        return false;
                    }
                    JsonObject memberSchema = knownProperty ? properties.getAsJsonObject(member.getKey())
                        : additional != null && additional.isJsonObject() ? additional.getAsJsonObject() : null;
                    if (memberSchema != null && !matches(member.getValue(), memberSchema, definitions)) return false;
                }
                return true;
            default:
                try {
                    return matchesScalar(value, schema);
                } catch (IllegalArgumentException exception) {
                    return false;
                }
        }
    }

    private static JsonElement generate(JsonObject schema, JsonObject definitions, Set<String> visiting) {
        if (schema.has("examples")) return schema.getAsJsonArray("examples")
            .get(0);
        if (schema.has("$ref")) {
            String reference = schema.get("$ref")
                .getAsString();
            String name = reference.substring(reference.lastIndexOf('/') + 1);
            if (!visiting.add(name)) return null;
            JsonElement result = generate(definitions.getAsJsonObject(name), definitions, visiting);
            visiting.remove(name);
            return result;
        }
        if (schema.has("anyOf")) {
            JsonElement result = generate(nonNullSchema(schema), definitions, visiting);
            return result == null ? JsonNull.INSTANCE : result;
        }
        switch (type(schema)) {
            case "object":
                JsonObject object = new JsonObject();
                if (schema.has("properties")) {
                    for (Map.Entry<String, JsonElement> property : schema.getAsJsonObject("properties")
                        .entrySet()) {
                        JsonElement value = generate(
                            property.getValue()
                                .getAsJsonObject(),
                            definitions,
                            visiting);
                        if (value == null) return null;
                        object.add(property.getKey(), value);
                    }
                } else if (schema.has("additionalProperties")) {
                    JsonElement value = generate(schema.getAsJsonObject("additionalProperties"), definitions, visiting);
                    if (value != null) {
                        String key = scalarExample(schema.getAsJsonObject("propertyNames")).getAsString();
                        object.add(key, value);
                    }
                }
                return object;
            case "array":
                JsonArray array = new JsonArray();
                JsonElement item = generate(schema.getAsJsonObject("items"), definitions, visiting);
                if (item != null) array.add(item);
                return array;
            default:
                return scalarExample(schema);
        }
    }

    private static JsonElement scalarExample(JsonObject schema) {
        if (schema.has("examples")) return schema.getAsJsonArray("examples")
            .get(0);
        if (schema.has("enum")) return schema.getAsJsonArray("enum")
            .get(0);
        switch (type(schema)) {
            case "string":
                if (schema.has("format") && schema.get("format")
                    .getAsString()
                    .equals("uuid")) {
                    return new JsonPrimitive("00000000-0000-0000-0000-000000000001");
                }
                return new JsonPrimitive(
                    schema.has("maxLength") && schema.get("maxLength")
                        .getAsInt() == 1 ? "x" : "string");
            case "integer", "number":
                return new JsonPrimitive(0);
            case "boolean":
                return new JsonPrimitive(false);
            case "null":
                return JsonNull.INSTANCE;
            default:
                throw new IllegalArgumentException("Cannot generate example for " + schema);
        }
    }

    private static JsonObject nonNullSchema(JsonObject schema) {
        return schema.has("anyOf") ? schema.getAsJsonArray("anyOf")
            .get(0)
            .getAsJsonObject() : schema;
    }

    private static String type(JsonObject schema) {
        return schema.has("type") ? schema.get("type")
            .getAsString() : "";
    }
}
