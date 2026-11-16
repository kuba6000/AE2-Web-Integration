package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

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
    @NotNull CraftAction action, long cooldownSeconds) {}
