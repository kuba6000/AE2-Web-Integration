package pl.kuba6000.ae2webintegration.core.standingorders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderTestData.key;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture amounts.
class ConditionEvaluationTest {

    private static final StableKey PLATES = key(10);

    @Test
    void ratioComparisonKeepsTheFractionAndUsesAnInclusiveLowerBound() {
        StockRatio ratio = new StockRatio(key(11), 2, 3);
        Condition below = new Condition.StockComparison(PLATES, ratio, false);
        Condition atLeast = new Condition.StockComparison(PLATES, ratio, true);
        StandingOrderTestData.Stock stock = new StandingOrderTestData.Stock(0).with(PLATES, 2);
        assertEquals(Truth.UNKNOWN, below.evaluate(stock));
        stock.with(ratio.reference(), 4);
        assertEquals(Truth.TRUE, below.evaluate(stock));
        assertEquals(Truth.FALSE, atLeast.evaluate(stock));
        stock.with(ratio.reference(), 3);
        assertEquals(Truth.FALSE, below.evaluate(stock));
        assertEquals(Truth.TRUE, atLeast.evaluate(stock));
    }

    @Test
    void stockBelowIsStrict() {
        Condition below = new Condition.StockBelow(PLATES, 10);

        assertEquals(Truth.TRUE, below.evaluate(new StandingOrderTestData.Stock(0).with(PLATES, 9)));
        assertEquals(Truth.FALSE, below.evaluate(new StandingOrderTestData.Stock(0).with(PLATES, 10)));
        assertEquals(Truth.FALSE, below.evaluate(new StandingOrderTestData.Stock(0).with(PLATES, 11)));
    }

    @Test
    void stockAtLeastIsInclusive() {
        Condition atLeast = new Condition.StockAtLeast(PLATES, 10);

        assertEquals(Truth.FALSE, atLeast.evaluate(new StandingOrderTestData.Stock(0).with(PLATES, 9)));
        assertEquals(Truth.TRUE, atLeast.evaluate(new StandingOrderTestData.Stock(0).with(PLATES, 10)));
        assertEquals(Truth.TRUE, atLeast.evaluate(new StandingOrderTestData.Stock(0).with(PLATES, 11)));
    }

    @Test
    void unknownStockIsDistinctFromZero() {
        StandingOrderTestData.Stock unknown = new StandingOrderTestData.Stock(0);
        StandingOrderTestData.Stock zero = new StandingOrderTestData.Stock(0).with(PLATES, 0);

        assertEquals(Truth.UNKNOWN, new Condition.StockBelow(PLATES, 1).evaluate(unknown));
        assertEquals(Truth.TRUE, new Condition.StockBelow(PLATES, 1).evaluate(zero));
        assertEquals(Truth.UNKNOWN, new Condition.StockAtLeast(PLATES, 1).evaluate(unknown));
        assertEquals(Truth.FALSE, new Condition.StockAtLeast(PLATES, 1).evaluate(zero));
    }

    @Test
    void stockOfOtherResourcesDoesNotAnswerForThisOne() {
        StandingOrderTestData.Stock other = new StandingOrderTestData.Stock(0).with(key(11), 0);

        assertEquals(Truth.UNKNOWN, new Condition.StockBelow(PLATES, 1).evaluate(other));
    }

    @Test
    void idleCpusAtLeastComparesEligibleIdleCount() {
        Condition two = new Condition.IdleCpusAtLeast(2);

        assertEquals(Truth.FALSE, two.evaluate(new StandingOrderTestData.Stock(1)));
        assertEquals(Truth.TRUE, two.evaluate(new StandingOrderTestData.Stock(2)));
        assertEquals(Truth.TRUE, two.evaluate(new StandingOrderTestData.Stock(3)));
    }

    @Test
    void amountsAtTheLongLimitsCompareWithoutOverflow() {
        StandingOrderTestData.Stock full = new StandingOrderTestData.Stock(0).with(PLATES, Long.MAX_VALUE);

        assertEquals(Truth.TRUE, new Condition.StockAtLeast(PLATES, Long.MAX_VALUE).evaluate(full));
        assertEquals(Truth.FALSE, new Condition.StockBelow(PLATES, Long.MAX_VALUE).evaluate(full));
    }

    @ParameterizedTest(name = "all({0}, {1}) = {2}")
    @CsvSource({ "T,T,TRUE", "T,F,FALSE", "F,T,FALSE", "F,F,FALSE", "T,U,UNKNOWN", "U,T,UNKNOWN", "F,U,FALSE",
        "U,F,FALSE", "U,U,UNKNOWN" })
    void allFollowsThreeValuedAnd(char left, char right, Truth expected) {
        assertEquals(expected, new Condition.All(Arrays.asList(literal(left), literal(right))).evaluate(world()));
    }

    @ParameterizedTest(name = "any({0}, {1}) = {2}")
    @CsvSource({ "T,T,TRUE", "T,F,TRUE", "F,T,TRUE", "F,F,FALSE", "T,U,TRUE", "U,T,TRUE", "F,U,UNKNOWN", "U,F,UNKNOWN",
        "U,U,UNKNOWN" })
    void anyFollowsThreeValuedOr(char left, char right, Truth expected) {
        assertEquals(expected, new Condition.Any(Arrays.asList(literal(left), literal(right))).evaluate(world()));
    }

    @ParameterizedTest(name = "not({0}) = {1}")
    @CsvSource({ "T,FALSE", "F,TRUE", "U,UNKNOWN" })
    void notNegatesAndKeepsUnknown(char operand, Truth expected) {
        assertEquals(expected, new Condition.Not(literal(operand)).evaluate(world()));
    }

    @Test
    void nestedGroupsComposeAcrossKinds() {
        // plastic < 10 AND (ethylene >= 50 OR NOT idle CPUs >= 2)
        StableKey plastic = key(20);
        StableKey ethylene = key(21);
        Condition condition = new Condition.All(
            Arrays.asList(
                new Condition.StockBelow(plastic, 10),
                new Condition.Any(
                    Arrays.asList(
                        new Condition.StockAtLeast(ethylene, 50),
                        new Condition.Not(new Condition.IdleCpusAtLeast(2))))));

        assertEquals(
            Truth.TRUE,
            condition.evaluate(
                new StandingOrderTestData.Stock(1).with(plastic, 5)
                    .with(ethylene, 10)));
        assertEquals(
            Truth.FALSE,
            condition.evaluate(
                new StandingOrderTestData.Stock(2).with(plastic, 5)
                    .with(ethylene, 10)));
        assertEquals(
            Truth.TRUE,
            condition.evaluate(
                new StandingOrderTestData.Stock(2).with(plastic, 5)
                    .with(ethylene, 50)));
        assertEquals(Truth.UNKNOWN, condition.evaluate(new StandingOrderTestData.Stock(2).with(plastic, 5)));
        assertEquals(Truth.FALSE, condition.evaluate(new StandingOrderTestData.Stock(2).with(plastic, 10)));
    }

    @Test
    void groupsDoNotAliasTheListTheyWereBuiltFrom() {
        Condition truth = literal('T');
        List<Condition> children = new ArrayList<>(Collections.singletonList(truth));
        Condition.All all = new Condition.All(children);

        children.add(literal('F'));

        assertEquals(Collections.singletonList(truth), all.children());
        assertEquals(Truth.TRUE, all.evaluate(world()));
    }

    /** T is stored 5 >= 1, F is stored 0 >= 1, U has no stored amount. */
    private static Condition literal(char symbol) {
        return switch (symbol) {
            case 'T' -> new Condition.StockAtLeast(key(1), 1);
            case 'F' -> new Condition.StockAtLeast(key(2), 1);
            default -> new Condition.StockAtLeast(key(3), 1);
        };
    }

    private static StandingOrderTestData.Stock world() {
        return new StandingOrderTestData.Stock(0).with(key(1), 5)
            .with(key(2), 0);
    }
}
