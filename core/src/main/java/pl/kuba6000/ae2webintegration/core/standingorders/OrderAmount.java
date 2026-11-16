package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/**
 * What an {@link AmountPolicy} decided to order.
 *
 * @param outcome whether to order, skip, or the amount could not be computed
 * @param amount  positive for ORDER, zero otherwise
 */
@Desugar
public record OrderAmount(@NotNull Outcome outcome, long amount) {

    public enum Outcome {
        ORDER,
        NOTHING_TO_ORDER,
        OVERFLOW
    }

    private static final OrderAmount NOTHING_TO_ORDER = new OrderAmount(Outcome.NOTHING_TO_ORDER, 0);
    private static final OrderAmount OVERFLOW = new OrderAmount(Outcome.OVERFLOW, 0);

    public static @NotNull OrderAmount order(long amount) {
        return new OrderAmount(Outcome.ORDER, amount);
    }

    public static @NotNull OrderAmount nothingToOrder() {
        return NOTHING_TO_ORDER;
    }

    public static @NotNull OrderAmount overflow() {
        return OVERFLOW;
    }
}
