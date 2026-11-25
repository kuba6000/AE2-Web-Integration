package pl.kuba6000.ae2webintegration.core.standingorders;

import java.util.LinkedHashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * The user-editable part of a standing order. Persistent identity (key, network, position, creator) is not part of
 * it. Instances are valid only after {@link StandingOrderValidator} or {@link StandingOrderCodec#decode} accepted
 * them.
 *
 * @param name            display name, 1 to {@value StandingOrderValidator#MAX_NAME_LENGTH} trimmed characters
 * @param enabled         whether the order is evaluated
 * @param trigger         when to craft
 * @param action          what to craft
 * @param cooldownSeconds minimum seconds after a submission before the next one
 */
@Desugar
public record StandingOrderDefinition(@NotNull String name, boolean enabled, @NotNull Condition trigger,
    @NotNull CraftAction action, long cooldownSeconds) {

    /** Resources this order needs to read or craft, including both sides of every stock comparison. */
    public @NotNull Set<StableKey> resources() {
        Set<StableKey> result = new LinkedHashSet<>();
        result.add(action.resource());
        if (action.amount() instanceof AmountPolicy.FillToRatio relative) result.add(
            relative.ratio()
                .reference());
        collect(trigger, result);
        return result;
    }

    private static void collect(Condition condition, Set<StableKey> resources) {
        if (condition instanceof Condition.StockBelow below) resources.add(below.resource());
        else if (condition instanceof Condition.StockAtLeast atLeast) resources.add(atLeast.resource());
        else if (condition instanceof Condition.StockComparison compared) {
            resources.add(compared.resource());
            resources.add(
                compared.ratio()
                    .reference());
        } else if (condition instanceof Condition.All all) {
            for (Condition child : all.children()) collect(child, resources);
        } else if (condition instanceof Condition.Any any) {
            for (Condition child : any.children()) collect(child, resources);
        } else if (condition instanceof Condition.Not not) collect(not.child(), resources);
    }
}
