package pl.kuba6000.ae2webintegration.core.standingorders;

import java.math.BigInteger;
import java.util.OptionalLong;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** How much a standing order crafts per submission. The implementations below are the complete set. */
public interface AmountPolicy {

    /**
     * @param stored current stored amount of the crafted resource; ignored by {@link Fixed}
     * @param stock  quantities from the same observation, used by relative targets
     */
    @NotNull
    OrderAmount orderAmount(long stored, @NotNull ConditionInput stock);

    /** Fill up to the scaled reference stock, rounded up, subject to the batch cap. */
    @Desugar
    record FillToRatio(@NotNull StockRatio ratio, long maxBatch) implements AmountPolicy {

        @Override
        public @NotNull OrderAmount orderAmount(long stored, @NotNull ConditionInput stock) {
            OptionalLong reference = stock.storedAmount(ratio.reference());
            if (!reference.isPresent()) return OrderAmount.unknownStock();
            BigInteger deficit = ratio.target(reference.getAsLong())
                .subtract(BigInteger.valueOf(stored));
            return deficit.signum() > 0 ? OrderAmount.order(
                deficit.min(BigInteger.valueOf(maxBatch))
                    .longValueExact())
                : OrderAmount.nothingToOrder();
        }
    }

    /** Always the same batch. */
    @Desugar
    record Fixed(long batch) implements AmountPolicy {

        @Override
        public @NotNull OrderAmount orderAmount(long stored, @NotNull ConditionInput stock) {
            return OrderAmount.order(batch);
        }
    }

    /** The shortfall to {@code target}, at most {@code maxBatch} per submission. */
    @Desugar
    record FillTo(long target, long maxBatch) implements AmountPolicy {

        @Override
        public @NotNull OrderAmount orderAmount(long stored, @NotNull ConditionInput stock) {
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
