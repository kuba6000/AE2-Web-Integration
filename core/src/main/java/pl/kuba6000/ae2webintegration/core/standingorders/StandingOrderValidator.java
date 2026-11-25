package pl.kuba6000.ae2webintegration.core.standingorders;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException.Reason;

/**
 * The single validator of standing order definitions, applied to every definition that enters the system, whether it
 * comes from an HTTP request or from storage. Format and limits are checked by {@link #validate}; server support for
 * crafting options by {@link #validateCapabilities}.
 */
public final class StandingOrderValidator {

    /** Longest accepted name, in UTF-16 characters; the crafting history keeps the name of a submitting order. */
    public static final int MAX_NAME_LENGTH = CraftingRequester.MAX_ORDER_NAME_LENGTH;
    /** The root condition is at depth 1. */
    public static final int MAX_TREE_DEPTH = 4;
    /** Counts every condition, groups included. */
    public static final int MAX_TREE_NODES = 32;
    public static final int MAX_IDLE_CPUS = 1024;
    /** One week. */
    public static final long MAX_COOLDOWN_SECONDS = 7L * 24 * 60 * 60;

    private StandingOrderValidator() {}

    /**
     * Checks format and limits; the first violation is reported, in a fixed order: name, cooldown, trigger tree in
     * document order, then the action amounts. Server support for crafting options is not part of this check, so a
     * stored definition stays valid when the server's capabilities change; see {@link #validateCapabilities}.
     */
    public static void validate(@NotNull StandingOrderDefinition definition) throws InvalidStandingOrderException {
        validateName(definition.name());
        if (definition.cooldownSeconds() < 0 || definition.cooldownSeconds() > MAX_COOLDOWN_SECONDS) {
            throw new InvalidStandingOrderException(Reason.COOLDOWN_OUT_OF_RANGE, "cooldownSeconds");
        }
        validateCondition(definition.trigger(), "trigger", 1, 0);
        validateAmount(
            definition.action()
                .amount());
    }

    /**
     * Checks that the server supports the crafting options the definition enables, for creating or editing a
     * definition and for the engine to judge a stored one.
     *
     * @param capabilities server-wide native features; an option is accepted only when its capability is true
     */
    public static void validateCapabilities(@NotNull StandingOrderDefinition definition,
        @NotNull Map<ServerCapability, Boolean> capabilities) throws InvalidStandingOrderException {
        if (!Boolean.TRUE.equals(capabilities.get(ServerCapability.CPU_SELECTION_MODE))) {
            if (definition.action()
                .cpu()
                .machineOnly())
                throw new InvalidStandingOrderException(Reason.CAPABILITY_UNAVAILABLE, "action.cpu.type");
            requireUnfilteredCpuCounts(definition.trigger(), "trigger");
        }
        CraftingOptions options = definition.action()
            .options();
        if (options.lightMode() && !Boolean.TRUE.equals(capabilities.get(ServerCapability.CRAFTING_LIGHT_MODE))) {
            throw new InvalidStandingOrderException(Reason.CAPABILITY_UNAVAILABLE, "action.options.lightMode");
        }
        if (options.ignoreMissing()
            && !Boolean.TRUE.equals(capabilities.get(ServerCapability.CRAFTING_IGNORE_MISSING))) {
            throw new InvalidStandingOrderException(Reason.CAPABILITY_UNAVAILABLE, "action.options.ignoreMissing");
        }
    }

    private static void requireUnfilteredCpuCounts(Condition condition, String path)
        throws InvalidStandingOrderException {
        if (condition instanceof Condition.IdleCpusAtLeast idle && idle.playerOnly())
            throw new InvalidStandingOrderException(Reason.CAPABILITY_UNAVAILABLE, path + ".type");
        if (condition instanceof Condition.Not not) requireUnfilteredCpuCounts(not.child(), path + ".child");
        List<Condition> children;
        if (condition instanceof Condition.All all) children = all.children();
        else if (condition instanceof Condition.Any any) children = any.children();
        else return;
        for (int i = 0; i < children.size(); i++)
            requireUnfilteredCpuCounts(children.get(i), path + ".children[" + i + "]");
    }

    private static void validateName(@NotNull String name) throws InvalidStandingOrderException {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new InvalidStandingOrderException(Reason.NAME_LENGTH, "name");
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) {
                throw new InvalidStandingOrderException(Reason.NAME_INVALID_CHARACTER, "name");
            }
        }
        if (isSpace(name.charAt(0)) || isSpace(name.charAt(name.length() - 1))) {
            throw new InvalidStandingOrderException(Reason.NAME_NOT_TRIMMED, "name");
        }
    }

    private static boolean isSpace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    /** Visits in document order; returns the node count after this subtree. */
    private static int validateCondition(Condition condition, String path, int depth, int nodesBefore)
        throws InvalidStandingOrderException {
        int nodes = nodesBefore + 1;
        if (depth > MAX_TREE_DEPTH) throw new InvalidStandingOrderException(Reason.TREE_TOO_DEEP, path);
        if (nodes > MAX_TREE_NODES) throw new InvalidStandingOrderException(Reason.TREE_TOO_LARGE, path);
        if (condition instanceof Condition.StockBelow below) {
            requirePositive(below.amount(), path + ".amount");
        } else if (condition instanceof Condition.StockAtLeast atLeast) {
            requirePositive(atLeast.amount(), path + ".amount");
        } else if (condition instanceof Condition.StockComparison compared) {
            validateRatio(compared.ratio(), path);
        } else if (condition instanceof Condition.IdleCpusAtLeast idle) {
            if (idle.count() < 1 || idle.count() > MAX_IDLE_CPUS) {
                throw new InvalidStandingOrderException(Reason.IDLE_CPU_COUNT_OUT_OF_RANGE, path + ".count");
            }
        } else if (condition instanceof Condition.All all) {
            nodes = validateChildren(all.children(), path, depth, nodes);
        } else if (condition instanceof Condition.Any any) {
            nodes = validateChildren(any.children(), path, depth, nodes);
        } else if (condition instanceof Condition.Not not) {
            nodes = validateCondition(not.child(), path + ".child", depth + 1, nodes);
        } else {
            throw new IllegalStateException("Unsupported condition " + condition.getClass());
        }
        return nodes;
    }

    private static int validateChildren(List<Condition> children, String path, int depth, int nodesBefore)
        throws InvalidStandingOrderException {
        if (children.isEmpty()) throw new InvalidStandingOrderException(Reason.EMPTY_GROUP, path + ".children");
        int nodes = nodesBefore;
        for (int index = 0; index < children.size(); index++) {
            nodes = validateCondition(children.get(index), path + ".children[" + index + "]", depth + 1, nodes);
        }
        return nodes;
    }

    private static void validateAmount(AmountPolicy amount) throws InvalidStandingOrderException {
        if (amount instanceof AmountPolicy.Fixed fixed) {
            requirePositive(fixed.batch(), "action.amount.batch");
        } else if (amount instanceof AmountPolicy.FillTo fillTo) {
            requirePositive(fillTo.target(), "action.amount.target");
            requirePositive(fillTo.maxBatch(), "action.amount.maxBatch");
        } else if (amount instanceof AmountPolicy.FillToRatio relative) {
            validateRatio(relative.ratio(), "action.amount");
            requirePositive(relative.maxBatch(), "action.amount.maxBatch");
        } else {
            throw new IllegalStateException("Unsupported amount policy " + amount.getClass());
        }
    }

    private static void validateRatio(StockRatio ratio, String path) throws InvalidStandingOrderException {
        requirePositive(ratio.numerator(), path + ".numerator");
        requirePositive(ratio.denominator(), path + ".denominator");
    }

    private static void requirePositive(long value, String path) throws InvalidStandingOrderException {
        if (value <= 0) throw new InvalidStandingOrderException(Reason.AMOUNT_NOT_POSITIVE, path);
    }
}
