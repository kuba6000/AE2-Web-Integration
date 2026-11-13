package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** How much a standing order crafts per submission. The implementations below are the complete set. */
public interface AmountPolicy {

    /**
     * @param stored current stored amount of the crafted resource; ignored by {@link Fixed}
     */
    @NotNull
    OrderAmount orderAmount(long stored);

    /** Always the same batch. */
    @Desugar
    record Fixed(long batch) implements AmountPolicy {

        @Override
        public @NotNull OrderAmount orderAmount(long stored) {
            return OrderAmount.order(batch);
        }
    }

    /** The shortfall to {@code target}, at most {@code maxBatch} per submission. */
    @Desugar
    record FillTo(long target, long maxBatch) implements AmountPolicy {

        @Override
        public @NotNull OrderAmount orderAmount(long stored) {
            long missing;
            try {
                missing = Math.subtractExact(target, stored);
            } catch (ArithmeticException overflow) {
                return OrderAmount.overflow();
            }
            if (missing <= 0) return OrderAmount.nothingToOrder();
            return OrderAmount.order(Math.min(missing, maxBatch));
        }
    }
}
