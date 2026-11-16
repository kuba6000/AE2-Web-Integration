package pl.kuba6000.ae2webintegration.core.standingorders;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.NO_CAPABILITIES;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.definition;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.fixedAction;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.key;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.leaf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException.Reason;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture values and the documented limits.
class StandingOrderValidatorTest {

    private static InvalidStandingOrderException reject(StandingOrderDefinition definition) {
        return assertThrows(InvalidStandingOrderException.class, () -> StandingOrderValidator.validate(definition));
    }

    private static InvalidStandingOrderException rejectCapabilities(StandingOrderDefinition definition,
        Map<ServerCapability, Boolean> capabilities) {
        return assertThrows(
            InvalidStandingOrderException.class,
            () -> StandingOrderValidator.validateCapabilities(definition, capabilities));
    }

    private static void accept(StandingOrderDefinition definition) {
        assertDoesNotThrow(() -> StandingOrderValidator.validate(definition));
    }

    private static Condition nots(int depth) {
        Condition condition = leaf();
        for (int level = 1; level < depth; level++) condition = new Condition.Not(condition);
        return condition;
    }

    private static Condition flat(int leaves) {
        List<Condition> children = new ArrayList<>();
        for (int index = 0; index < leaves; index++) children.add(leaf());
        return new Condition.All(children);
    }

    @Test
    void acceptsEveryConditionAndActionKind() {
        Condition trigger = new Condition.All(
            Arrays.asList(
                new Condition.StockBelow(key(1), 1),
                new Condition.Any(
                    Arrays.asList(
                        new Condition.StockAtLeast(key(2), 1),
                        new Condition.Not(new Condition.IdleCpusAtLeast(1))))));

        accept(definition(trigger));
        accept(
            definition(
                trigger,
                new CraftAction(
                    key(3),
                    new AmountPolicy.FillTo(10, 5),
                    new CraftingOptions(true, true),
                    CpuTarget.specific(key(4)))));
    }

    @ParameterizedTest
    @ValueSource(strings = { "a", "Keep plates stocked", "Zlecenie stałe", "a b", "xé" })
    void acceptsTrimmedNamesOfAllowedLength(String name) {
        accept(new StandingOrderDefinition(name, true, leaf(), fixedAction(key(1)), 0));
    }

    @Test
    void acceptsNameAtTheLengthLimit() {
        String name = String.join("", Collections.nCopies(StandingOrderValidator.MAX_NAME_LENGTH, "n"));

        accept(new StandingOrderDefinition(name, true, leaf(), fixedAction(key(1)), 0));
    }

    @Test
    void rejectsEmptyAndOverlongNames() {
        String tooLong = String.join("", Collections.nCopies(StandingOrderValidator.MAX_NAME_LENGTH + 1, "n"));

        for (String name : new String[] { "", tooLong }) {
            InvalidStandingOrderException failure = reject(
                new StandingOrderDefinition(name, true, leaf(), fixedAction(key(1)), 0));
            assertEquals(Reason.NAME_LENGTH, failure.reason());
            assertEquals("name", failure.path());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { " a", "a ", " ", " a", "a　", " " })
    void rejectsNamesThatAreNotTrimmed(String name) {
        InvalidStandingOrderException failure = reject(
            new StandingOrderDefinition(name, true, leaf(), fixedAction(key(1)), 0));

        assertEquals(Reason.NAME_NOT_TRIMMED, failure.reason());
        assertEquals("name", failure.path());
    }

    @ParameterizedTest
    @ValueSource(strings = { "a\nb", "a\tb", "\u0000", "a\u007fb" })
    void rejectsControlCharactersInNames(String name) {
        InvalidStandingOrderException failure = reject(
            new StandingOrderDefinition(name, true, leaf(), fixedAction(key(1)), 0));

        assertEquals(Reason.NAME_INVALID_CHARACTER, failure.reason());
        assertEquals("name", failure.path());
    }

    @Test
    void cooldownMustStayInsideTheDocumentedRange() {
        for (long cooldown : new long[] { 0, 1, StandingOrderValidator.MAX_COOLDOWN_SECONDS }) {
            accept(new StandingOrderDefinition("n", true, leaf(), fixedAction(key(1)), cooldown));
        }
        for (long cooldown : new long[] { -1, StandingOrderValidator.MAX_COOLDOWN_SECONDS + 1, Long.MIN_VALUE,
            Long.MAX_VALUE }) {
            InvalidStandingOrderException failure = reject(
                new StandingOrderDefinition("n", true, leaf(), fixedAction(key(1)), cooldown));
            assertEquals(Reason.COOLDOWN_OUT_OF_RANGE, failure.reason());
            assertEquals("cooldownSeconds", failure.path());
        }
    }

    @Test
    void treeDepthIsLimitedToTheDocumentedMaximum() {
        accept(definition(nots(StandingOrderValidator.MAX_TREE_DEPTH)));

        InvalidStandingOrderException failure = reject(definition(nots(StandingOrderValidator.MAX_TREE_DEPTH + 1)));

        assertEquals(Reason.TREE_TOO_DEEP, failure.reason());
        assertEquals("trigger.child.child.child.child", failure.path());
    }

    @Test
    void depthCountsGroupsLikeAnyOtherNode() {
        Condition level3 = new Condition.All(Collections.singletonList(leaf()));
        Condition level2 = new Condition.Any(Collections.singletonList(level3));
        Condition level1 = new Condition.All(Collections.singletonList(level2));
        accept(definition(level1));

        InvalidStandingOrderException failure = reject(definition(new Condition.Not(level1)));

        assertEquals(Reason.TREE_TOO_DEEP, failure.reason());
    }

    @Test
    void nodeCountIsLimitedToTheDocumentedMaximum() {
        accept(definition(flat(StandingOrderValidator.MAX_TREE_NODES - 1)));

        InvalidStandingOrderException failure = reject(definition(flat(StandingOrderValidator.MAX_TREE_NODES)));

        assertEquals(Reason.TREE_TOO_LARGE, failure.reason());
        assertEquals("trigger.children[31]", failure.path());
    }

    @Test
    void nonPositiveAmountsAreRejectedWherePathsPointAtTheField() {
        InvalidStandingOrderException below = reject(definition(new Condition.StockBelow(key(1), 0)));
        assertEquals(Reason.AMOUNT_NOT_POSITIVE, below.reason());
        assertEquals("trigger.amount", below.path());

        InvalidStandingOrderException atLeast = reject(
            definition(new Condition.All(Arrays.asList(leaf(), new Condition.StockAtLeast(key(1), -5)))));
        assertEquals(Reason.AMOUNT_NOT_POSITIVE, atLeast.reason());
        assertEquals("trigger.children[1].amount", atLeast.path());

        InvalidStandingOrderException fixed = reject(
            definition(
                leaf(),
                new CraftAction(key(1), new AmountPolicy.Fixed(0), StandingOrderTestData.PLAIN, CpuTarget.any())));
        assertEquals(Reason.AMOUNT_NOT_POSITIVE, fixed.reason());
        assertEquals("action.amount.batch", fixed.path());

        InvalidStandingOrderException target = reject(
            definition(
                leaf(),
                new CraftAction(key(1), new AmountPolicy.FillTo(0, 5), StandingOrderTestData.PLAIN, CpuTarget.any())));
        assertEquals(Reason.AMOUNT_NOT_POSITIVE, target.reason());
        assertEquals("action.amount.target", target.path());

        InvalidStandingOrderException maxBatch = reject(
            definition(
                leaf(),
                new CraftAction(key(1), new AmountPolicy.FillTo(5, -1), StandingOrderTestData.PLAIN, CpuTarget.any())));
        assertEquals(Reason.AMOUNT_NOT_POSITIVE, maxBatch.reason());
        assertEquals("action.amount.maxBatch", maxBatch.path());
    }

    @Test
    void largestPositiveAmountsAreAccepted() {
        accept(
            definition(
                new Condition.StockAtLeast(key(1), Long.MAX_VALUE),
                new CraftAction(
                    key(1),
                    new AmountPolicy.FillTo(Long.MAX_VALUE, Long.MAX_VALUE),
                    StandingOrderTestData.PLAIN,
                    CpuTarget.any())));
    }

    @Test
    void idleCpuCountMustBeBetweenOneAndTheDocumentedMaximum() {
        accept(definition(new Condition.IdleCpusAtLeast(1)));
        accept(definition(new Condition.IdleCpusAtLeast(StandingOrderValidator.MAX_IDLE_CPUS)));

        for (long count : new long[] { 0, -1, StandingOrderValidator.MAX_IDLE_CPUS + 1, Long.MAX_VALUE }) {
            InvalidStandingOrderException failure = reject(definition(new Condition.IdleCpusAtLeast(count)));
            assertEquals(Reason.IDLE_CPU_COUNT_OUT_OF_RANGE, failure.reason());
            assertEquals("trigger.count", failure.path());
        }
    }

    @Test
    void groupsMustHaveChildren() {
        InvalidStandingOrderException all = reject(definition(new Condition.All(Collections.emptyList())));
        assertEquals(Reason.EMPTY_GROUP, all.reason());
        assertEquals("trigger.children", all.path());

        InvalidStandingOrderException any = reject(
            definition(new Condition.Not(new Condition.Any(Collections.emptyList()))));
        assertEquals(Reason.EMPTY_GROUP, any.reason());
        assertEquals("trigger.child.children", any.path());
    }

    @Test
    void structuralValidationIgnoresServerCapabilities() {
        accept(
            definition(
                leaf(),
                new CraftAction(key(1), new AmountPolicy.Fixed(1), new CraftingOptions(true, true), CpuTarget.any())));
    }

    @Test
    void craftingOptionsRequireTheMatchingServerCapability() {
        CraftAction light = new CraftAction(
            key(1),
            new AmountPolicy.Fixed(1),
            new CraftingOptions(true, false),
            CpuTarget.any());
        CraftAction missing = new CraftAction(
            key(1),
            new AmountPolicy.Fixed(1),
            new CraftingOptions(false, true),
            CpuTarget.any());

        InvalidStandingOrderException lightFailure = rejectCapabilities(definition(leaf(), light), NO_CAPABILITIES);
        assertEquals(Reason.CAPABILITY_UNAVAILABLE, lightFailure.reason());
        assertEquals("action.options.lightMode", lightFailure.path());

        InvalidStandingOrderException missingFailure = rejectCapabilities(definition(leaf(), missing), NO_CAPABILITIES);
        assertEquals(Reason.CAPABILITY_UNAVAILABLE, missingFailure.reason());
        assertEquals("action.options.ignoreMissing", missingFailure.path());
    }

    @Test
    void capabilityReportedFalseCountsAsUnavailable() {
        Map<ServerCapability, Boolean> capabilities = new HashMap<>();
        capabilities.put(ServerCapability.CRAFTING_LIGHT_MODE, false);
        capabilities.put(ServerCapability.CRAFTING_IGNORE_MISSING, true);
        CraftAction light = new CraftAction(
            key(1),
            new AmountPolicy.Fixed(1),
            new CraftingOptions(true, false),
            CpuTarget.any());

        assertEquals(
            Reason.CAPABILITY_UNAVAILABLE,
            rejectCapabilities(definition(leaf(), light), capabilities).reason());
    }

    @Test
    void disabledOptionsNeedNoCapability() {
        assertDoesNotThrow(() -> StandingOrderValidator.validateCapabilities(definition(leaf()), NO_CAPABILITIES));
    }

    @Test
    void anAvailableCapabilityAllowsItsOptionOnly() {
        Map<ServerCapability, Boolean> capabilities = new HashMap<>();
        capabilities.put(ServerCapability.CRAFTING_IGNORE_MISSING, true);
        CraftAction allowed = new CraftAction(
            key(1),
            new AmountPolicy.Fixed(1),
            new CraftingOptions(false, true),
            CpuTarget.any());
        CraftAction both = new CraftAction(
            key(1),
            new AmountPolicy.Fixed(1),
            new CraftingOptions(true, true),
            CpuTarget.any());

        assertDoesNotThrow(
            () -> StandingOrderValidator.validateCapabilities(definition(leaf(), allowed), capabilities));
        assertEquals("action.options.lightMode", rejectCapabilities(definition(leaf(), both), capabilities).path());
    }

    @Test
    void failureCarriesAStableCodeAndHumanReadableMessage() {
        InvalidStandingOrderException failure = reject(definition(new Condition.StockBelow(key(1), 0)));

        assertEquals(
            "AMOUNT_NOT_POSITIVE",
            failure.reason()
                .name());
        assertTrue(
            failure.getMessage()
                .contains("AMOUNT_NOT_POSITIVE"),
            failure.getMessage());
        assertTrue(
            failure.getMessage()
                .contains("trigger.amount"),
            failure.getMessage());
    }
}
