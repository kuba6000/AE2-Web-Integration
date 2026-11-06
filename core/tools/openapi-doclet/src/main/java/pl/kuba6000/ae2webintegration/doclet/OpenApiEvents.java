package pl.kuba6000.ae2webintegration.doclet;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Presents event payload schemas as readable documentation while preserving the SSE wire format. */
final class OpenApiEvents {

    private static final Gson JSON = new GsonBuilder().serializeNulls()
        .create();

    private OpenApiEvents() {}

    static void complete(JsonObject document) {
        JsonObject definitions = document.getAsJsonObject("components")
            .getAsJsonObject("schemas");
        for (JsonElement path : document.getAsJsonObject("paths")
            .asMap()
            .values()) {
            for (JsonElement value : path.getAsJsonObject()
                .asMap()
                .values()) {
                JsonObject operation = value.getAsJsonObject();
                JsonObject events = operation.getAsJsonObject("x-sse-events");
                if (events == null) continue;
                JsonObject examples = new JsonObject();
                StringBuilder description = new StringBuilder(
                    operation.get("description")
                        .getAsString());
                for (Map.Entry<String, JsonElement> entry : events.entrySet()) {
                    JsonObject event = entry.getValue()
                        .getAsJsonObject();
                    JsonObject schema = event.getAsJsonObject("schema");
                    JsonElement example = event.has("example") ? event.get("example")
                        : OpenApiExamples.generate(schema, definitions, new HashSet<>());
                    if (example != null && !OpenApiExamples.matches(example, schema, definitions))
                        throw new IllegalArgumentException(
                            "@eventExample does not match payload schema: " + entry.getKey());
                    if (example == null)
                        throw new IllegalArgumentException("Cannot generate finite SSE example: " + entry.getKey());
                    JsonObject wireExample = new JsonObject();
                    wireExample.addProperty("summary", entry.getKey());
                    wireExample
                        .addProperty("value", "event: " + entry.getKey() + "\ndata: " + JSON.toJson(example) + "\n\n");
                    examples.add(entry.getKey(), wireExample);
                    description.append("\n\n### Event `")
                        .append(entry.getKey())
                        .append("`\n\n")
                        .append(
                            event.get("description")
                                .getAsString());
                    appendFields(description, resolve(schema, definitions), definitions);
                }
                boolean stream = false;
                for (Map.Entry<String, JsonElement> response : operation.getAsJsonObject("responses")
                    .entrySet()) {
                    JsonObject content = response.getValue()
                        .getAsJsonObject()
                        .getAsJsonObject("content");
                    if (content == null || !content.has("text/event-stream")) continue;
                    JsonObject media = content.getAsJsonObject("text/event-stream");
                    JsonObject wireSchema = new JsonObject();
                    wireSchema.addProperty("type", "string");
                    media.add("schema", wireSchema);
                    media.add("examples", examples);
                    stream = true;
                }
                if (!stream) throw new IllegalArgumentException("@event requires a text/event-stream response");
                operation.addProperty(
                    "description",
                    description.toString()
                        .strip());
            }
        }
    }

    private static void appendFields(StringBuilder text, JsonObject schema, JsonObject definitions) {
        table(text, schema);
        Set<String> nested = new LinkedHashSet<>();
        JsonObject properties = schema.getAsJsonObject("properties");
        if (properties == null) return;
        for (JsonElement property : properties.asMap()
            .values()) references(property.getAsJsonObject(), nested);
        for (String name : nested) {
            text.append("\n\n#### `")
                .append(name)
                .append("`\n");
            table(text, definitions.getAsJsonObject(name));
        }
    }

    private static void table(StringBuilder text, JsonObject schema) {
        JsonObject properties = schema.getAsJsonObject("properties");
        if (properties == null || properties.isEmpty()) {
            text.append("\n\nData: `{}`.");
            return;
        }
        text.append("\n\n| Field | Type | Required | Description |\n| --- | --- | --- | --- |\n");
        for (Map.Entry<String, JsonElement> field : properties.entrySet()) {
            JsonObject value = field.getValue()
                .getAsJsonObject();
            boolean required = schema.has("required") && schema.getAsJsonArray("required")
                .asList()
                .stream()
                .anyMatch(
                    item -> item.getAsString()
                        .equals(field.getKey()));
            text.append("| `")
                .append(field.getKey())
                .append("` | ")
                .append(escape(type(value)))
                .append(" | ")
                .append(required ? "Yes" : "No")
                .append(" | ")
                .append(
                    value.has("description") ? escape(
                        value.get("description")
                            .getAsString())
                        : "")
                .append(" |\n");
        }
    }

    private static String type(JsonObject schema) {
        if (schema.has("$ref")) return referenceName(schema);
        if (schema.has("anyOf")) return schema.getAsJsonArray("anyOf")
            .asList()
            .stream()
            .map(value -> type(value.getAsJsonObject()))
            .reduce((left, right) -> left + " or " + right)
            .orElse("");
        String name = schema.get("type")
            .getAsString();
        if (name.equals("array")) return "array of " + type(schema.getAsJsonObject("items"));
        if (name.equals("object") && schema.has("additionalProperties")
            && schema.get("additionalProperties")
                .isJsonObject())
            return "map of " + type(schema.getAsJsonObject("additionalProperties"));
        return name;
    }

    private static void references(JsonObject schema, Set<String> names) {
        if (schema.has("$ref")) names.add(referenceName(schema));
        for (String key : new String[] { "items", "additionalProperties" }) {
            if (schema.has(key) && schema.get(key)
                .isJsonObject()) references(schema.getAsJsonObject(key), names);
        }
        if (schema.has("anyOf"))
            for (JsonElement value : schema.getAsJsonArray("anyOf")) references(value.getAsJsonObject(), names);
    }

    private static JsonObject resolve(JsonObject schema, JsonObject definitions) {
        return schema.has("$ref") ? definitions.getAsJsonObject(referenceName(schema)) : schema;
    }

    private static String referenceName(JsonObject schema) {
        String reference = schema.get("$ref")
            .getAsString();
        return reference.substring(reference.lastIndexOf('/') + 1);
    }

    private static String escape(String text) {
        return text.replace("|", "\\|")
            .replace("\n", " ")
            .replace("\r", " ");
    }
}
