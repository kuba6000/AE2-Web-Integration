package pl.kuba6000.ae2webintegration.core.standingorders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidMagicNumbers") // Arbitrary fixture amounts.
class AmountPolicyTest {

    private final StandingOrderTestData.Stock stock = new StandingOrderTestData.Stock(0);

    @Test
    void fractionalTargetRoundsUpAndCapsAProductLargerThanLong() {
        StockRatio ratio = new StockRatio(StandingOrderTestData.key(2), 3, 2);
        AmountPolicy fill = new AmountPolicy.FillToRatio(ratio, 10);
        assertEquals(OrderAmount.unknownStock(), fill.orderAmount(0, stock));
        stock.with(ratio.reference(), 5);
        assertEquals(OrderAmount.order(1), fill.orderAmount(7, stock));
        assertEquals(OrderAmount.nothingToOrder(), fill.orderAmount(8, stock));
        stock.with(ratio.reference(), Long.MAX_VALUE);
        assertEquals(OrderAmount.order(10), fill.orderAmount(Long.MAX_VALUE, stock));
    }

    @Test
    void fixedOrdersItsBatchWhateverIsStored() {
        AmountPolicy fixed = new AmountPolicy.Fixed(64);

        assertEquals(OrderAmount.order(64), fixed.orderAmount(0, stock));
        assertEquals(OrderAmount.order(64), fixed.orderAmount(1_000_000, stock));
    }

    @Test
    void fillToOrdersTheMissingAmountWhenItFitsTheBatch() {
        AmountPolicy fillTo = new AmountPolicy.FillTo(1000, 500);

        assertEquals(OrderAmount.order(300), fillTo.orderAmount(700, stock));
        assertEquals(OrderAmount.order(500), fillTo.orderAmount(500, stock));
    }

    @Test
    void fillToCapsTheOrderAtMaxBatch() {
        AmountPolicy fillTo = new AmountPolicy.FillTo(1000, 100);

        assertEquals(OrderAmount.order(100), fillTo.orderAmount(0, stock));
        assertEquals(OrderAmount.order(100), fillTo.orderAmount(899, stock));
        assertEquals(OrderAmount.order(100), fillTo.orderAmount(900, stock));
        assertEquals(OrderAmount.order(99), fillTo.orderAmount(901, stock));
    }

    @Test
    void fillToOrdersNothingAtOrAboveTheTarget() {
        AmountPolicy fillTo = new AmountPolicy.FillTo(1000, 100);

        assertEquals(OrderAmount.nothingToOrder(), fillTo.orderAmount(1000, stock));
        assertEquals(OrderAmount.nothingToOrder(), fillTo.orderAmount(1001, stock));
        assertEquals(OrderAmount.nothingToOrder(), fillTo.orderAmount(Long.MAX_VALUE, stock));
    }

    @Test
    void fillToHandlesTheLargestRepresentableGap() {
        AmountPolicy fillTo = new AmountPolicy.FillTo(Long.MAX_VALUE, Long.MAX_VALUE);

        assertEquals(OrderAmount.order(Long.MAX_VALUE), fillTo.orderAmount(0, stock));
        assertEquals(OrderAmount.order(1), fillTo.orderAmount(Long.MAX_VALUE - 1, stock));
    }

    @Test
    void fillToReportsOverflowInsteadOfWrapping() {
        AmountPolicy fillTo = new AmountPolicy.FillTo(Long.MAX_VALUE, 100);

        assertEquals(OrderAmount.overflow(), fillTo.orderAmount(-1, stock));
        assertEquals(OrderAmount.overflow(), fillTo.orderAmount(Long.MIN_VALUE, stock));
    }

    @Test
    void outcomesWithoutAnOrderCarryNoAmount() {
        assertEquals(
            OrderAmount.Outcome.ORDER,
            OrderAmount.order(5)
                .outcome());
        assertEquals(
            5,
            OrderAmount.order(5)
                .amount());
        assertEquals(
            OrderAmount.Outcome.NOTHING_TO_ORDER,
            OrderAmount.nothingToOrder()
                .outcome());
        assertEquals(
            0,
            OrderAmount.nothingToOrder()
                .amount());
        assertEquals(
            OrderAmount.Outcome.OVERFLOW,
            OrderAmount.overflow()
                .outcome());
        assertEquals(
            0,
            OrderAmount.overflow()
                .amount());
    }
}
