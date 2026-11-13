package pl.kuba6000.ae2webintegration.core.standingorders;

import java.util.OptionalLong;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** The observed state a {@link Condition} is evaluated against. */
public interface ConditionInput {

    /** Stored amount of a resource, or empty when it is unknown; an unknown amount is not zero. */
    @NotNull
    OptionalLong storedAmount(@NotNull StableKey resource);

    /** Idle crafting CPUs that a standing order's submission may use. */
    int eligibleIdleCpus();
}
