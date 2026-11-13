package pl.kuba6000.ae2webintegration.core.standingorders;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

@SuppressWarnings({ "PMD.AvoidMagicNumbers", "UnstableApiUsage" }) // Arbitrary fixture values; StableKey hashes through
                                                                   // PrimitiveSink.
final class StandingOrderTestData {

    static final CraftingOptions PLAIN = new CraftingOptions(false, false);
    static final Map<ServerCapability, Boolean> NO_CAPABILITIES = Collections.emptyMap();

    private StandingOrderTestData() {}

    static @NotNull StableKey key(int seed) {
        return StableKey.create(sink -> sink.putInt(seed));
    }

    static @NotNull Map<ServerCapability, Boolean> allCapabilities() {
        Map<ServerCapability, Boolean> capabilities = new HashMap<>();
        capabilities.put(ServerCapability.CRAFTING_LIGHT_MODE, true);
        capabilities.put(ServerCapability.CRAFTING_IGNORE_MISSING, true);
        return capabilities;
    }

    static @NotNull CraftAction fixedAction(@NotNull StableKey resource) {
        return new CraftAction(resource, new AmountPolicy.Fixed(64), PLAIN, CpuTarget.any());
    }

    static @NotNull StandingOrderDefinition definition(@NotNull Condition trigger) {
        return new StandingOrderDefinition("Keep plates", true, trigger, fixedAction(key(1)), 0);
    }

    static @NotNull StandingOrderDefinition definition(@NotNull Condition trigger, @NotNull CraftAction action) {
        return new StandingOrderDefinition("Keep plates", true, trigger, action, 0);
    }

    static @NotNull Condition leaf() {
        return new Condition.StockBelow(key(2), 100);
    }

    /** Stored amounts by resource; a resource without an entry is unknown, not zero. */
    static final class Stock implements ConditionInput {

        private final Map<StableKey, Long> amounts = new HashMap<>();
        private final int idleCpus;

        Stock(int idleCpus) {
            this.idleCpus = idleCpus;
        }

        Stock with(@NotNull StableKey resource, long amount) {
            amounts.put(resource, amount);
            return this;
        }

        @Override
        public @NotNull OptionalLong storedAmount(@NotNull StableKey resource) {
            Long amount = amounts.get(resource);
            return amount == null ? OptionalLong.empty() : OptionalLong.of(amount);
        }

        @Override
        public int eligibleIdleCpus() {
            return idleCpus;
        }
    }
}
