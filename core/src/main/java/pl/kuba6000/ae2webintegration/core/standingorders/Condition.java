package pl.kuba6000.ae2webintegration.core.standingorders;

import java.math.BigInteger;
import java.util.List;
import java.util.OptionalLong;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;
import com.google.common.collect.ImmutableList;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * When a standing order acts. The implementations below are the complete set; the validator and codec handle each
 * of them explicitly.
 */
public interface Condition {

    /** Pure evaluation; a group is UNKNOWN only when no child decides the result. */
    @NotNull
    Truth evaluate(@NotNull ConditionInput input);

    /** Stored amount of the resource is below {@code amount}. */
    @Desugar
    record StockBelow(@NotNull StableKey resource, long amount) implements Condition {

        @Override
        public @NotNull Truth evaluate(@NotNull ConditionInput input) {
            OptionalLong stored = input.storedAmount(resource);
            return stored.isPresent() ? Truth.of(stored.getAsLong() < amount) : Truth.UNKNOWN;
        }
    }

    /** Stored amount of the resource is at least {@code amount}. */
    @Desugar
    record StockAtLeast(@NotNull StableKey resource, long amount) implements Condition {

        @Override
        public @NotNull Truth evaluate(@NotNull ConditionInput input) {
            OptionalLong stored = input.storedAmount(resource);
            return stored.isPresent() ? Truth.of(stored.getAsLong() >= amount) : Truth.UNKNOWN;
        }
    }

    /** Compares stock with the scaled stock of another resource in the same observation. */
    @Desugar
    record StockComparison(@NotNull StableKey resource, @NotNull StockRatio ratio, boolean atLeast)
        implements Condition {

        @Override
        public @NotNull Truth evaluate(@NotNull ConditionInput input) {
            OptionalLong actual = input.storedAmount(resource);
            OptionalLong reference = input.storedAmount(ratio.reference());
            if (!actual.isPresent() || !reference.isPresent()) return Truth.UNKNOWN;
            boolean meetsTarget = BigInteger.valueOf(actual.getAsLong())
                .compareTo(ratio.target(reference.getAsLong())) >= 0;
            return Truth.of(atLeast == meetsTarget);
        }
    }

    /** At least {@code count} eligible crafting CPUs are idle. */
    @Desugar
    record IdleCpusAtLeast(long count, boolean playerOnly) implements Condition {

        public IdleCpusAtLeast(long count) {
            this(count, false);
        }

        @Override
        public @NotNull Truth evaluate(@NotNull ConditionInput input) {
            return Truth.of(input.eligibleIdleCpus(playerOnly) >= count);
        }
    }

    /** Every child holds. */
    @Desugar
    record All(@NotNull List<Condition> children) implements Condition {

        public All {
            children = ImmutableList.copyOf(children);
        }

        @Override
        public @NotNull Truth evaluate(@NotNull ConditionInput input) {
            boolean unknown = false;
            for (Condition child : children) {
                Truth result = child.evaluate(input);
                if (result == Truth.FALSE) return Truth.FALSE;
                if (result == Truth.UNKNOWN) unknown = true;
            }
            return unknown ? Truth.UNKNOWN : Truth.TRUE;
        }
    }

    /** At least one child holds. */
    @Desugar
    record Any(@NotNull List<Condition> children) implements Condition {

        public Any {
            children = ImmutableList.copyOf(children);
        }

        @Override
        public @NotNull Truth evaluate(@NotNull ConditionInput input) {
            boolean unknown = false;
            for (Condition child : children) {
                Truth result = child.evaluate(input);
                if (result == Truth.TRUE) return Truth.TRUE;
                if (result == Truth.UNKNOWN) unknown = true;
            }
            return unknown ? Truth.UNKNOWN : Truth.FALSE;
        }
    }

    /** The child does not hold; an unknown child stays unknown. */
    @Desugar
    record Not(@NotNull Condition child) implements Condition {

        @Override
        public @NotNull Truth evaluate(@NotNull ConditionInput input) {
            return child.evaluate(input)
                .negate();
        }
    }
}
