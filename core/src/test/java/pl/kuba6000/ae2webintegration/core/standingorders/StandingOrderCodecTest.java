package pl.kuba6000.ae2webintegration.core.standingorders;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.NO_CAPABILITIES;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.PLAIN;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.allCapabilities;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.key;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException.Reason;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderCodec.Encoded;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture values and the documented limits.
class StandingOrderCodecTest {

    /** Slot 0 is the crafted resource key(1), slot 1 is the trigger resource key(2). */
    private static final String VALID = "{\"name\":\"Keep plates\",\"enabled\":true,\"cooldownSeconds\":30,"
        + "\"trigger\":{\"type\":\"stockBelow\",\"resource\":1,\"amount\":100},"
        + "\"action\":{\"resource\":0,\"amount\":{\"type\":\"fixed\",\"batch\":64},"
        + "\"options\":{\"lightMode\":false,\"ignoreMissing\":false},\"cpu\":{\"type\":\"any\"}}}";
    private static final List<StableKey> VALID_RESOURCES = Arrays.asList(key(1), key(2));

    private static StandingOrderDefinition decode(String json) throws InvalidStandingOrderException {
        return StandingOrderCodec.decode(new Encoded(StandingOrderCodec.VERSION, json, VALID_RESOURCES));
    }

    private static String valid(String from, String to) {
        assertTrue(VALID.contains(from), from);
        return VALID.replace(from, to);
    }

    private static StandingOrderDefinition roundTrip(StandingOrderDefinition definition)
        throws InvalidStandingOrderException {
        return StandingOrderCodec.decode(StandingOrderCodec.encode(definition));
    }

    @Test
    void decodesTheVersionOneDocumentFormat() throws Exception {
        StandingOrderDefinition expected = new StandingOrderDefinition(
            "Keep plates",
            true,
            new Condition.StockBelow(key(2), 100),
            new CraftAction(key(1), new AmountPolicy.Fixed(64), PLAIN, CpuTarget.any()),
            30);

        assertEquals(expected, decode(VALID));
    }

    @Test
    void roundTripsEveryConditionKind() throws Exception {
        Condition trigger = new Condition.All(
            Arrays.asList(
                new Condition.StockBelow(key(2), 10),
                new Condition.Any(
                    Arrays.asList(
                        new Condition.StockAtLeast(key(3), 5),
                        new Condition.Not(new Condition.IdleCpusAtLeast(2)))),
                new Condition.IdleCpusAtLeast(1)));
        StandingOrderDefinition definition = StandingOrderTestData.definition(trigger);

        assertEquals(definition, roundTrip(definition));
    }

    @Test
    void roundTripsFillToWithSpecificCpuAndOptions() throws Exception {
        StandingOrderDefinition definition = new StandingOrderDefinition(
            "Plastic",
            false,
            new Condition.StockBelow(key(2), 10_000),
            new CraftAction(
                key(1),
                new AmountPolicy.FillTo(50_000, 8_000),
                new CraftingOptions(true, true),
                CpuTarget.specific(key(9))),
            3600);

        assertEquals(definition, roundTrip(definition));
    }

    @Test
    void roundTripsLimitValuesAndEscapedUnicodeNames() throws Exception {
        for (String name : new String[] { "quote \" backslash \\ slash /", "Zlecenie stałe ☃", "👍 ok", "</script>" }) {
            StandingOrderDefinition definition = new StandingOrderDefinition(
                name,
                true,
                new Condition.StockAtLeast(key(2), Long.MAX_VALUE),
                new CraftAction(
                    key(1),
                    new AmountPolicy.FillTo(Long.MAX_VALUE, Long.MAX_VALUE),
                    PLAIN,
                    CpuTarget.any()),
                StandingOrderValidator.MAX_COOLDOWN_SECONDS);

            assertEquals(definition, roundTrip(definition), name);
        }
    }

    @Test
    void encodingIsDeterministic() {
        StandingOrderDefinition definition = StandingOrderTestData.definition(
            new Condition.All(Arrays.asList(new Condition.StockBelow(key(2), 1), new Condition.IdleCpusAtLeast(1))));

        Encoded first = StandingOrderCodec.encode(definition);
        Encoded second = StandingOrderCodec.encode(definition);

        assertEquals(first, second);
        assertEquals(StandingOrderCodec.VERSION, first.version());
    }

    @Test
    void resourcesAreOrderedDistinctSlotsStartingWithTheCraftedResource() {
        StableKey crafted = key(1);
        StableKey first = key(2);
        StableKey second = key(3);
        StandingOrderDefinition definition = StandingOrderTestData.definition(
            new Condition.All(
                Arrays.asList(
                    new Condition.StockBelow(first, 1),
                    new Condition.Any(
                        Arrays.asList(new Condition.StockAtLeast(second, 1), new Condition.StockBelow(crafted, 5))),
                    new Condition.Not(new Condition.StockAtLeast(first, 9)))));

        Encoded encoded = StandingOrderCodec.encode(definition);

        assertEquals(Arrays.asList(crafted, first, second), encoded.resources());
    }

    @Test
    void documentReferencesResourcesBySlotOnly() {
        StandingOrderDefinition definition = StandingOrderTestData.definition(new Condition.StockBelow(key(2), 7));

        Encoded encoded = StandingOrderCodec.encode(definition);

        JsonObject document = new JsonParser().parse(encoded.json())
            .getAsJsonObject();
        JsonObject trigger = document.getAsJsonObject("trigger");
        assertEquals(
            encoded.resources()
                .indexOf(key(2)),
            trigger.get("resource")
                .getAsInt());
        assertEquals(
            0,
            document.getAsJsonObject("action")
                .get("resource")
                .getAsInt());
        for (StableKey resource : encoded.resources()) {
            assertFalse(
                encoded.json()
                    .contains(resource.toString()),
                "resource keys belong to the slot table");
        }
    }

    @Test
    void cpuKeyStaysInsideTheDocument() {
        StableKey cpu = key(9);
        StandingOrderDefinition definition = StandingOrderTestData.definition(
            new Condition.StockBelow(key(2), 7),
            new CraftAction(key(1), new AmountPolicy.Fixed(1), PLAIN, CpuTarget.specific(cpu)));

        Encoded encoded = StandingOrderCodec.encode(definition);

        assertTrue(
            encoded.json()
                .contains(cpu.toString()));
        assertFalse(
            encoded.resources()
                .contains(cpu));
    }

    @Test
    void decodeAcceptsFieldsInAnyOrder() throws Exception {
        String reordered = "{\"action\":{\"cpu\":{\"type\":\"any\"},\"options\":{\"ignoreMissing\":false,"
            + "\"lightMode\":false},\"amount\":{\"batch\":64,\"type\":\"fixed\"},\"resource\":0},"
            + "\"trigger\":{\"amount\":100,\"resource\":1,\"type\":\"stockBelow\"},\"cooldownSeconds\":30,"
            + "\"enabled\":true,\"name\":\"Keep plates\"}";

        assertEquals(decode(VALID), decode(reordered));
    }

    private static InvalidStandingOrderException reject(String json, List<StableKey> resources) {
        return assertThrows(
            InvalidStandingOrderException.class,
            () -> StandingOrderCodec.decode(new Encoded(StandingOrderCodec.VERSION, json, resources)));
    }

    static Stream<Arguments> rejectedDocuments() {
        String deepNots = String.join("", Collections.nCopies(100_000, "{\"type\":\"not\",\"child\":"));
        String deepArrays = "{\"name\":" + String.join("", Collections.nCopies(100_000, "["));
        List<StableKey> oneKey = Collections.singletonList(key(1));
        return Stream.of(
            // syntax
            Arguments.of("empty document", "", VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of("not json", "not json", VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of("truncated", "{\"name\":\"a\"", VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of("trailing data", VALID + " 1", VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of("two documents", VALID + VALID, VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of(
                "lenient single quotes",
                VALID.replace("\"name\"", "'name'"),
                VALID_RESOURCES,
                Reason.MALFORMED_JSON,
                ""),
            Arguments.of("unquoted name", valid("\"enabled\"", "enabled"), VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of("array root", "[]", VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of("number root", "42", VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of("null root", "null", VALID_RESOURCES, Reason.MALFORMED_JSON, ""),
            Arguments.of(
                "duplicate field",
                valid("\"enabled\":true,", "\"enabled\":true,\"enabled\":false,"),
                VALID_RESOURCES,
                Reason.DUPLICATE_FIELD,
                "enabled"),
            Arguments.of(
                "duplicate nested field",
                valid("\"amount\":100", "\"amount\":100,\"amount\":1"),
                VALID_RESOURCES,
                Reason.DUPLICATE_FIELD,
                "trigger.amount"),
            // document nesting and tree size
            Arguments.of(
                "array bomb",
                deepArrays,
                VALID_RESOURCES,
                Reason.TREE_TOO_DEEP,
                "name" + String.join("", Collections.nCopies(63, "[0]"))),
            Arguments.of(
                "not chain bomb",
                valid("{\"type\":\"stockBelow\",\"resource\":1,\"amount\":100}", deepNots),
                VALID_RESOURCES,
                Reason.TREE_TOO_DEEP,
                "trigger" + String.join("", Collections.nCopies(63, ".child"))),
            Arguments.of(
                "five nested nots",
                valid(
                    "{\"type\":\"stockBelow\",\"resource\":1,\"amount\":100}",
                    "{\"type\":\"not\",\"child\":{\"type\":\"not\",\"child\":{\"type\":\"not\",\"child\":"
                        + "{\"type\":\"not\",\"child\":{\"type\":\"stockBelow\",\"resource\":1,\"amount\":1}}}}}"),
                VALID_RESOURCES,
                Reason.TREE_TOO_DEEP,
                "trigger.child.child.child.child"),
            Arguments
                .of(
                    "thirty-three nodes",
                    valid(
                        "{\"type\":\"stockBelow\",\"resource\":1,\"amount\":100}",
                        "{\"type\":\"all\",\"children\":[" + String
                            .join(",", Collections.nCopies(32, "{\"type\":\"stockBelow\",\"resource\":1,\"amount\":1}"))
                            + "]}"),
                    VALID_RESOURCES,
                    Reason.TREE_TOO_LARGE,
                    "trigger.children[31]"),
            // version and structure
            Arguments.of(
                "empty group",
                valid("{\"type\":\"stockBelow\",\"resource\":1,\"amount\":100}", "{\"type\":\"any\",\"children\":[]}"),
                oneKey,
                Reason.EMPTY_GROUP,
                "trigger.children"),
            Arguments.of(
                "unknown root field",
                valid("\"enabled\":true,", "\"enabled\":true,\"extra\":1,"),
                VALID_RESOURCES,
                Reason.UNKNOWN_FIELD,
                "extra"),
            Arguments.of(
                "unknown condition field",
                valid("\"amount\":100", "\"amount\":100,\"extra\":1"),
                VALID_RESOURCES,
                Reason.UNKNOWN_FIELD,
                "trigger.extra"),
            Arguments.of(
                "unknown option field",
                valid("\"ignoreMissing\":false", "\"ignoreMissing\":false,\"extra\":1"),
                VALID_RESOURCES,
                Reason.UNKNOWN_FIELD,
                "action.options.extra"),
            Arguments.of(
                "missing name",
                valid("\"name\":\"Keep plates\",", ""),
                VALID_RESOURCES,
                Reason.MISSING_FIELD,
                "name"),
            Arguments.of(
                "missing enabled",
                valid("\"enabled\":true,", ""),
                VALID_RESOURCES,
                Reason.MISSING_FIELD,
                "enabled"),
            Arguments.of(
                "missing cooldown",
                valid("\"cooldownSeconds\":30,", ""),
                VALID_RESOURCES,
                Reason.MISSING_FIELD,
                "cooldownSeconds"),
            Arguments.of(
                "missing amount",
                valid(",\"amount\":100", ""),
                VALID_RESOURCES,
                Reason.MISSING_FIELD,
                "trigger.amount"),
            Arguments.of(
                "missing options",
                valid("\"options\":{\"lightMode\":false,\"ignoreMissing\":false},", ""),
                VALID_RESOURCES,
                Reason.MISSING_FIELD,
                "action.options"),
            Arguments.of(
                "missing batch",
                valid("\"batch\":64", "\"other\":64"),
                VALID_RESOURCES,
                Reason.MISSING_FIELD,
                "action.amount.batch"),
            Arguments.of(
                "missing condition kind",
                valid("\"type\":\"stockBelow\",", ""),
                VALID_RESOURCES,
                Reason.MISSING_FIELD,
                "trigger.type"),
            // types
            Arguments.of("numeric name", valid("\"Keep plates\"", "5"), VALID_RESOURCES, Reason.WRONG_TYPE, "name"),
            Arguments.of("null name", valid("\"Keep plates\"", "null"), VALID_RESOURCES, Reason.WRONG_TYPE, "name"),
            Arguments.of(
                "string flag",
                valid("\"enabled\":true", "\"enabled\":\"true\""),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "enabled"),
            Arguments.of(
                "numeric flag",
                valid("\"lightMode\":false", "\"lightMode\":0"),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "action.options.lightMode"),
            Arguments.of(
                "fractional amount",
                valid("\"amount\":100", "\"amount\":1.5"),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "trigger.amount"),
            Arguments.of(
                "exponent amount",
                valid("\"amount\":100", "\"amount\":1e2"),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "trigger.amount"),
            Arguments.of(
                "amount above long",
                valid("\"amount\":100", "\"amount\":9223372036854775808"),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "trigger.amount"),
            Arguments.of(
                "string amount",
                valid("\"amount\":100", "\"amount\":\"100\""),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "trigger.amount"),
            Arguments.of(
                "fractional cooldown",
                valid("\"cooldownSeconds\":30", "\"cooldownSeconds\":0.5"),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "cooldownSeconds"),
            Arguments.of(
                "trigger as array",
                valid("{\"type\":\"stockBelow\",\"resource\":1,\"amount\":100}", "[]"),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "trigger"),
            Arguments.of(
                "string slot",
                valid("\"resource\":1", "\"resource\":\"1\""),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "trigger.resource"),
            Arguments.of(
                "numeric kind",
                valid("\"type\":\"stockBelow\"", "\"type\":3"),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "trigger.type"),
            Arguments.of(
                "children not an array",
                valid("{\"type\":\"stockBelow\",\"resource\":1,\"amount\":100}", "{\"type\":\"all\",\"children\":{}}"),
                VALID_RESOURCES,
                Reason.WRONG_TYPE,
                "trigger.children"),
            // kinds
            Arguments.of(
                "unknown condition kind",
                valid("stockBelow", "stockMaybe"),
                VALID_RESOURCES,
                Reason.UNKNOWN_KIND,
                "trigger.type"),
            Arguments.of(
                "unknown amount kind",
                valid("\"type\":\"fixed\"", "\"type\":\"percent\""),
                VALID_RESOURCES,
                Reason.UNKNOWN_KIND,
                "action.amount.type"),
            Arguments.of(
                "unknown cpu kind",
                valid("\"cpu\":{\"type\":\"any\"}", "\"cpu\":{\"type\":\"largest\"}"),
                VALID_RESOURCES,
                Reason.UNKNOWN_KIND,
                "action.cpu.type"),
            Arguments.of(
                "case differs",
                valid("stockBelow", "StockBelow"),
                VALID_RESOURCES,
                Reason.UNKNOWN_KIND,
                "trigger.type"),
            Arguments.of(
                "malformed cpu key",
                valid("\"cpu\":{\"type\":\"any\"}", "\"cpu\":{\"type\":\"specific\",\"key\":\"bad\"}"),
                VALID_RESOURCES,
                Reason.INVALID_KEY,
                "action.cpu.key"),
            Arguments.of(
                "noncanonical cpu key",
                valid(
                    "\"cpu\":{\"type\":\"any\"}",
                    "\"cpu\":{\"type\":\"specific\",\"key\":\"AAAAAAAAAAAAAAAAAAAAAB\"}"),
                VALID_RESOURCES,
                Reason.INVALID_KEY,
                "action.cpu.key"),
            // slots
            Arguments.of(
                "negative trigger slot",
                valid("\"resource\":1", "\"resource\":-1"),
                VALID_RESOURCES,
                Reason.INVALID_RESOURCE_SLOT,
                "trigger.resource"),
            Arguments.of(
                "trigger slot past the table",
                valid("\"resource\":1", "\"resource\":2"),
                VALID_RESOURCES,
                Reason.INVALID_RESOURCE_SLOT,
                "trigger.resource"),
            Arguments.of(
                "slot beyond int",
                valid("\"resource\":1", "\"resource\":4294967297"),
                VALID_RESOURCES,
                Reason.INVALID_RESOURCE_SLOT,
                "trigger.resource"),
            Arguments.of(
                "action slot past the table",
                valid("\"resource\":0", "\"resource\":5"),
                VALID_RESOURCES,
                Reason.INVALID_RESOURCE_SLOT,
                "action.resource"),
            Arguments.of(
                "empty table",
                VALID,
                Collections.<StableKey>emptyList(),
                Reason.INVALID_RESOURCE_SLOT,
                "trigger.resource"),
            Arguments
                .of("duplicate table keys", VALID, Arrays.asList(key(1), key(1)), Reason.INVALID_RESOURCE_TABLE, ""),
            Arguments.of(
                "unreferenced slot",
                VALID,
                Arrays.asList(key(1), key(2), key(3)),
                Reason.INVALID_RESOURCE_TABLE,
                ""),
            // domain limits are enforced by the same decode
            Arguments.of(
                "zero amount",
                valid("\"amount\":100", "\"amount\":0"),
                VALID_RESOURCES,
                Reason.AMOUNT_NOT_POSITIVE,
                "trigger.amount"),
            Arguments.of(
                "negative batch",
                valid("\"batch\":64", "\"batch\":-64"),
                VALID_RESOURCES,
                Reason.AMOUNT_NOT_POSITIVE,
                "action.amount.batch"),
            Arguments.of("empty name", valid("\"Keep plates\"", "\"\""), VALID_RESOURCES, Reason.NAME_LENGTH, "name"),
            Arguments.of(
                "untrimmed name",
                valid("\"Keep plates\"", "\" Keep plates\""),
                VALID_RESOURCES,
                Reason.NAME_NOT_TRIMMED,
                "name"),
            Arguments.of(
                "control in name",
                valid("\"Keep plates\"", "\"Keep\\nplates\""),
                VALID_RESOURCES,
                Reason.NAME_INVALID_CHARACTER,
                "name"),
            Arguments.of(
                "negative cooldown",
                valid("\"cooldownSeconds\":30", "\"cooldownSeconds\":-1"),
                VALID_RESOURCES,
                Reason.COOLDOWN_OUT_OF_RANGE,
                "cooldownSeconds"),
            Arguments.of(
                "zero idle cpus",
                valid(
                    "{\"type\":\"stockBelow\",\"resource\":1,\"amount\":100}",
                    "{\"type\":\"idleCpusAtLeast\",\"count\":0}"),
                oneKey,
                Reason.IDLE_CPU_COUNT_OUT_OF_RANGE,
                "trigger.count"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedDocuments")
    void decodeRejectsInvalidDocumentsWithAStableReason(String description, String json, List<StableKey> resources,
        Reason reason, String path) {
        InvalidStandingOrderException failure = reject(json, resources);

        assertEquals(reason, failure.reason(), description);
        assertEquals(path, failure.path(), description);
    }

    @Test
    void decodeRejectsUnknownVersions() {
        for (int version : new int[] { 0, 2, -1, Integer.MAX_VALUE }) {
            InvalidStandingOrderException failure = assertThrows(
                InvalidStandingOrderException.class,
                () -> StandingOrderCodec.decode(new Encoded(version, VALID, VALID_RESOURCES)));

            assertEquals(Reason.UNSUPPORTED_VERSION, failure.reason());
            assertEquals("", failure.path());
        }
    }

    @Test
    void decodeAcceptsOptionsTheServerNoLongerSupports() throws Exception {
        String options = valid(
            "\"lightMode\":false,\"ignoreMissing\":false",
            "\"lightMode\":true,\"ignoreMissing\":true");

        StandingOrderDefinition stored = decode(options);

        assertEquals(
            new CraftingOptions(true, true),
            stored.action()
                .options());
        InvalidStandingOrderException failure = assertThrows(
            InvalidStandingOrderException.class,
            () -> StandingOrderValidator.validateCapabilities(stored, NO_CAPABILITIES));
        assertEquals(Reason.CAPABILITY_UNAVAILABLE, failure.reason());
        assertEquals("action.options.lightMode", failure.path());
        assertDoesNotThrow(() -> StandingOrderValidator.validateCapabilities(stored, allCapabilities()));
    }
}
