package pl.kuba6000.ae2webintegration.core.standingorders;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException.Reason;

/**
 * Versioned JSON form of a {@link StandingOrderDefinition}. The document refers to resources by index into a
 * separate list of distinct keys, so a store can map them to its own resource identifiers. {@link #decode} is the
 * inverse of {@link #encode} and also runs the {@link StandingOrderValidator}.
 */
public final class StandingOrderCodec {

    public static final int VERSION = 1;

    // The deepest valid document nests four conditions, each at most two levels (group array and element).
    private static final int MAX_DOCUMENT_NESTING = 64;

    private StandingOrderCodec() {}

    /**
     * An encoded definition.
     *
     * @param version   document format version
     * @param json      the document
     * @param resources distinct resource keys in slot order; the document's resource numbers index this list
     */
    @Desugar
    public record Encoded(int version, @NotNull String json, @NotNull List<StableKey> resources) {

        public Encoded {
            resources = ImmutableList.copyOf(resources);
        }
    }

    /** Slots are assigned in order of first use, starting with the crafted resource. */
    public static @NotNull Encoded encode(@NotNull StandingOrderDefinition definition) {
        Map<StableKey, Integer> slots = new LinkedHashMap<>();
        JsonObject action = encodeAction(definition.action(), slots);
        JsonObject trigger = encodeCondition(definition.trigger(), slots);
        JsonObject root = new JsonObject();
        root.addProperty("name", definition.name());
        root.addProperty("enabled", definition.enabled());
        root.addProperty("cooldownSeconds", definition.cooldownSeconds());
        root.add("trigger", trigger);
        root.add("action", action);
        return new Encoded(VERSION, root.toString(), new ArrayList<>(slots.keySet()));
    }

    private static int slot(Map<StableKey, Integer> slots, StableKey resource) {
        Integer existing = slots.get(resource);
        if (existing != null) return existing;
        int created = slots.size();
        slots.put(resource, created);
        return created;
    }

    private static JsonObject encodeAction(CraftAction action, Map<StableKey, Integer> slots) {
        JsonObject result = new JsonObject();
        result.addProperty("resource", slot(slots, action.resource()));
        JsonObject amount = encodeAmount(action.amount(), slots);
        result.add("amount", amount);
        JsonObject options = new JsonObject();
        CraftingOptions craftingOptions = action.options();
        options.addProperty("lightMode", craftingOptions.lightMode());
        options.addProperty("ignoreMissing", craftingOptions.ignoreMissing());
        result.add("options", options);
        JsonObject cpu = new JsonObject();
        StableKey selected = action.cpu()
            .cpu();
        if (selected == null) {
            cpu.addProperty(
                "type",
                action.cpu()
                    .machineOnly() ? "machineOnly" : "any");
        } else {
            cpu.addProperty("type", "specific");
            cpu.addProperty("key", selected.toString());
        }
        result.add("cpu", cpu);
        return result;
    }

    private static JsonObject encodeAmount(AmountPolicy policy, Map<StableKey, Integer> slots) {
        JsonObject amount = new JsonObject();
        if (policy instanceof AmountPolicy.Fixed fixed) {
            amount.addProperty("type", "fixed");
            amount.addProperty("batch", fixed.batch());
        } else if (policy instanceof AmountPolicy.FillTo fillTo) {
            amount.addProperty("type", "fillTo");
            amount.addProperty("target", fillTo.target());
            amount.addProperty("maxBatch", fillTo.maxBatch());
        } else if (policy instanceof AmountPolicy.FillToRatio relative) {
            amount.addProperty("type", "fillToRatio");
            encodeRatio(amount, relative.ratio(), slots);
            amount.addProperty("maxBatch", relative.maxBatch());
        } else {
            throw new IllegalStateException("Unsupported amount policy " + policy.getClass());
        }
        return amount;
    }

    private static void encodeRatio(JsonObject result, StockRatio ratio, Map<StableKey, Integer> slots) {
        result.addProperty("reference", slot(slots, ratio.reference()));
        result.addProperty("numerator", ratio.numerator());
        result.addProperty("denominator", ratio.denominator());
    }

    private static JsonObject encodeCondition(Condition condition, Map<StableKey, Integer> slots) {
        JsonObject result = new JsonObject();
        if (condition instanceof Condition.StockBelow below) {
            result.addProperty("type", "stockBelow");
            result.addProperty("resource", slot(slots, below.resource()));
            result.addProperty("amount", below.amount());
        } else if (condition instanceof Condition.StockAtLeast atLeast) {
            result.addProperty("type", "stockAtLeast");
            result.addProperty("resource", slot(slots, atLeast.resource()));
            result.addProperty("amount", atLeast.amount());
        } else if (condition instanceof Condition.StockComparison compared) {
            result.addProperty("type", compared.atLeast() ? "stockRatioAtLeast" : "stockRatioBelow");
            result.addProperty("resource", slot(slots, compared.resource()));
            encodeRatio(result, compared.ratio(), slots);
        } else if (condition instanceof Condition.IdleCpusAtLeast idle) {
            result.addProperty("type", idle.playerOnly() ? "idlePlayerCpusAtLeast" : "idleCpusAtLeast");
            result.addProperty("count", idle.count());
        } else if (condition instanceof Condition.All all) {
            result.addProperty("type", "all");
            result.add("children", encodeChildren(all.children(), slots));
        } else if (condition instanceof Condition.Any any) {
            result.addProperty("type", "any");
            result.add("children", encodeChildren(any.children(), slots));
        } else if (condition instanceof Condition.Not not) {
            result.addProperty("type", "not");
            result.add("child", encodeCondition(not.child(), slots));
        } else {
            throw new IllegalStateException("Unsupported condition " + condition.getClass());
        }
        return result;
    }

    private static JsonArray encodeChildren(List<Condition> children, Map<StableKey, Integer> slots) {
        JsonArray result = new JsonArray();
        for (Condition child : children) result.add(encodeCondition(child, slots));
        return result;
    }

    /**
     * Parses and validates a document. Every failure, including document syntax, is reported as an
     * {@link InvalidStandingOrderException}.
     * Server capabilities are deliberately not checked here: a stored definition must stay readable when the server
     * stops supporting one of its options ({@link StandingOrderValidator#validateCapabilities}).
     */
    public static @NotNull StandingOrderDefinition decode(@NotNull Encoded encoded)
        throws InvalidStandingOrderException {
        if (encoded.version() != VERSION) throw new InvalidStandingOrderException(Reason.UNSUPPORTED_VERSION, "");
        List<StableKey> resources = encoded.resources();
        if (new HashSet<>(resources).size() != resources.size()) {
            throw new InvalidStandingOrderException(Reason.INVALID_RESOURCE_TABLE, "");
        }
        JsonObject document = readDocument(encoded.json());
        Decoder decoder = new Decoder(resources);
        StandingOrderDefinition definition = decoder.definition(new Fields(document, ""));
        decoder.requireEverySlotUsed();
        StandingOrderValidator.validate(definition);
        return definition;
    }

    /**
     * Reads strictly: no lenient syntax, no trailing data, no duplicate names, and numbers stay exact. Integers
     * that fit a long become Long primitives; every other number becomes a Double so the typed pass rejects it.
     */
    private static JsonObject readDocument(String json) throws InvalidStandingOrderException {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            // Old Gson already fails a scalar root while peeking, so every non-object root is reported alike.
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new InvalidStandingOrderException(Reason.MALFORMED_JSON, "");
            }
            JsonObject root = readObject(reader, 1, "");
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new InvalidStandingOrderException(Reason.MALFORMED_JSON, "");
            }
            return root;
        } catch (IOException | IllegalStateException malformed) {
            throw new InvalidStandingOrderException(Reason.MALFORMED_JSON, "");
        }
    }

    private static JsonObject readObject(JsonReader reader, int depth, String path)
        throws IOException, InvalidStandingOrderException {
        if (depth > MAX_DOCUMENT_NESTING) throw new InvalidStandingOrderException(Reason.TREE_TOO_DEEP, path);
        JsonObject object = new JsonObject();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            String child = path.isEmpty() ? name : path + "." + name;
            if (object.has(name)) throw new InvalidStandingOrderException(Reason.DUPLICATE_FIELD, child);
            object.add(name, readValue(reader, depth, child));
        }
        reader.endObject();
        return object;
    }

    private static JsonArray readArray(JsonReader reader, int depth, String path)
        throws IOException, InvalidStandingOrderException {
        if (depth > MAX_DOCUMENT_NESTING) throw new InvalidStandingOrderException(Reason.TREE_TOO_DEEP, path);
        JsonArray array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) array.add(readValue(reader, depth, path + "[" + array.size() + "]"));
        reader.endArray();
        return array;
    }

    private static JsonElement readValue(JsonReader reader, int depth, String path)
        throws IOException, InvalidStandingOrderException {
        JsonToken token = reader.peek();
        if (token == JsonToken.BEGIN_OBJECT) return readObject(reader, depth + 1, path);
        if (token == JsonToken.BEGIN_ARRAY) return readArray(reader, depth + 1, path);
        if (token == JsonToken.STRING) return new JsonPrimitive(reader.nextString());
        if (token == JsonToken.BOOLEAN) return new JsonPrimitive(reader.nextBoolean());
        if (token == JsonToken.NUMBER) return number(reader.nextString());
        reader.nextNull();
        return JsonNull.INSTANCE;
    }

    private static JsonPrimitive number(String lexeme) {
        try {
            return new JsonPrimitive(Long.valueOf(lexeme));
        } catch (NumberFormatException notALong) {
            return new JsonPrimitive(Double.valueOf(lexeme));
        }
    }

    /** Typed access to one JSON object; every field must be read exactly once. */
    private static final class Fields {

        private final JsonObject object;
        private final String path;
        private final Set<String> unread = new LinkedHashSet<>();

        Fields(JsonObject object, String path) {
            this.object = object;
            this.path = path;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) unread.add(entry.getKey());
        }

        String path(String name) {
            return path.isEmpty() ? name : path + "." + name;
        }

        private JsonElement take(String name) throws InvalidStandingOrderException {
            JsonElement value = object.get(name);
            if (value == null) throw new InvalidStandingOrderException(Reason.MISSING_FIELD, path(name));
            unread.remove(name);
            return value;
        }

        String string(String name) throws InvalidStandingOrderException {
            JsonElement value = take(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
                .isString()) throw new InvalidStandingOrderException(Reason.WRONG_TYPE, path(name));
            return value.getAsString();
        }

        boolean bool(String name) throws InvalidStandingOrderException {
            JsonElement value = take(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
                .isBoolean()) throw new InvalidStandingOrderException(Reason.WRONG_TYPE, path(name));
            return value.getAsBoolean();
        }

        long integer(String name) throws InvalidStandingOrderException {
            JsonElement value = take(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive()
                .isNumber() || !(value.getAsNumber() instanceof Long))
                throw new InvalidStandingOrderException(Reason.WRONG_TYPE, path(name));
            return value.getAsLong();
        }

        Fields object(String name) throws InvalidStandingOrderException {
            JsonElement value = take(name);
            if (!value.isJsonObject()) throw new InvalidStandingOrderException(Reason.WRONG_TYPE, path(name));
            return new Fields(value.getAsJsonObject(), path(name));
        }

        List<Fields> children() throws InvalidStandingOrderException {
            String name = "children";
            JsonElement value = take(name);
            if (!value.isJsonArray()) throw new InvalidStandingOrderException(Reason.WRONG_TYPE, path(name));
            JsonArray array = value.getAsJsonArray();
            List<Fields> result = new ArrayList<>(array.size());
            for (int index = 0; index < array.size(); index++) {
                JsonElement element = array.get(index);
                String elementPath = path(name) + "[" + index + "]";
                if (!element.isJsonObject()) throw new InvalidStandingOrderException(Reason.WRONG_TYPE, elementPath);
                result.add(new Fields(element.getAsJsonObject(), elementPath));
            }
            return result;
        }

        void finish() throws InvalidStandingOrderException {
            if (!unread.isEmpty()) {
                throw new InvalidStandingOrderException(
                    Reason.UNKNOWN_FIELD,
                    path(
                        unread.iterator()
                            .next()));
            }
        }
    }

    private static final class Decoder {

        private final List<StableKey> resources;
        private final boolean[] used;

        Decoder(List<StableKey> resources) {
            this.resources = resources;
            used = new boolean[resources.size()];
        }

        StandingOrderDefinition definition(Fields fields) throws InvalidStandingOrderException {
            String name = fields.string("name");
            boolean enabled = fields.bool("enabled");
            long cooldownSeconds = fields.integer("cooldownSeconds");
            Condition trigger = condition(fields.object("trigger"));
            CraftAction action = action(fields.object("action"));
            fields.finish();
            return new StandingOrderDefinition(name, enabled, trigger, action, cooldownSeconds);
        }

        void requireEverySlotUsed() throws InvalidStandingOrderException {
            for (boolean slotUsed : used) {
                if (!slotUsed) throw new InvalidStandingOrderException(Reason.INVALID_RESOURCE_TABLE, "");
            }
        }

        private StableKey resource(Fields fields, String name) throws InvalidStandingOrderException {
            long slot = fields.integer(name);
            if (slot < 0 || slot >= resources.size()) {
                throw new InvalidStandingOrderException(Reason.INVALID_RESOURCE_SLOT, fields.path(name));
            }
            used[(int) slot] = true;
            return resources.get((int) slot);
        }

        private StockRatio ratio(Fields fields) throws InvalidStandingOrderException {
            return new StockRatio(
                resource(fields, "reference"),
                fields.integer("numerator"),
                fields.integer("denominator"));
        }

        private Condition condition(Fields fields) throws InvalidStandingOrderException {
            String type = fields.string("type");
            Condition result;
            switch (type) {
                case "stockBelow" -> {
                    StableKey resource = resource(fields, "resource");
                    result = new Condition.StockBelow(resource, fields.integer("amount"));
                }
                case "stockAtLeast" -> {
                    StableKey resource = resource(fields, "resource");
                    result = new Condition.StockAtLeast(resource, fields.integer("amount"));
                }
                case "stockRatioBelow", "stockRatioAtLeast" -> result = new Condition.StockComparison(
                    resource(fields, "resource"),
                    ratio(fields),
                    type.equals("stockRatioAtLeast"));
                case "idlePlayerCpusAtLeast" -> result = new Condition.IdleCpusAtLeast(fields.integer("count"), true);
                case "idleCpusAtLeast" -> result = new Condition.IdleCpusAtLeast(fields.integer("count"));
                case "all" -> result = new Condition.All(conditions(fields.children()));
                case "any" -> result = new Condition.Any(conditions(fields.children()));
                case "not" -> result = new Condition.Not(condition(fields.object("child")));
                default -> throw new InvalidStandingOrderException(Reason.UNKNOWN_KIND, fields.path("type"));
            }
            fields.finish();
            return result;
        }

        private List<Condition> conditions(List<Fields> children) throws InvalidStandingOrderException {
            List<Condition> result = new ArrayList<>(children.size());
            for (Fields child : children) result.add(condition(child));
            return result;
        }

        private CraftAction action(Fields fields) throws InvalidStandingOrderException {
            StableKey resource = resource(fields, "resource");
            AmountPolicy amount = amount(fields.object("amount"));
            Fields optionFields = fields.object("options");
            CraftingOptions options = new CraftingOptions(
                optionFields.bool("lightMode"),
                optionFields.bool("ignoreMissing"));
            optionFields.finish();
            CpuTarget cpu = cpu(fields.object("cpu"));
            fields.finish();
            return new CraftAction(resource, amount, options, cpu);
        }

        private AmountPolicy amount(Fields fields) throws InvalidStandingOrderException {
            String type = fields.string("type");
            AmountPolicy result;
            switch (type) {
                case "fixed" -> result = new AmountPolicy.Fixed(fields.integer("batch"));
                case "fillToRatio" -> result = new AmountPolicy.FillToRatio(ratio(fields), fields.integer("maxBatch"));
                case "fillTo" -> {
                    long target = fields.integer("target");
                    result = new AmountPolicy.FillTo(target, fields.integer("maxBatch"));
                }
                default -> throw new InvalidStandingOrderException(Reason.UNKNOWN_KIND, fields.path("type"));
            }
            fields.finish();
            return result;
        }

        private static CpuTarget cpu(Fields fields) throws InvalidStandingOrderException {
            String type = fields.string("type");
            CpuTarget result;
            switch (type) {
                case "any" -> result = CpuTarget.any();
                case "machineOnly" -> result = CpuTarget.machines();
                case "specific" -> {
                    String token = fields.string("key");
                    try {
                        result = CpuTarget.specific(StableKey.parse(token));
                    } catch (IllegalArgumentException invalid) {
                        throw new InvalidStandingOrderException(Reason.INVALID_KEY, fields.path("key"));
                    }
                }
                default -> throw new InvalidStandingOrderException(Reason.UNKNOWN_KIND, fields.path("type"));
            }
            fields.finish();
            return result;
        }
    }
}
