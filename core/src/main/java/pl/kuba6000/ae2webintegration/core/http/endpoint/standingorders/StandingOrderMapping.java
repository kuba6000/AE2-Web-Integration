package pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.standingorders.AmountPolicy;
import pl.kuba6000.ae2webintegration.core.standingorders.Condition;
import pl.kuba6000.ae2webintegration.core.standingorders.CpuTarget;
import pl.kuba6000.ae2webintegration.core.standingorders.CraftAction;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException.Reason;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderDefinition;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderValidator;
import pl.kuba6000.ae2webintegration.core.standingorders.StockRatio;

/** Maps the typed API representation to the domain; domain limits are shared with persisted definitions. */
final class StandingOrderMapping {

    private StandingOrderMapping() {}

    /**
     * @throws InvalidStandingOrderException the definition violates the format or a limit; the path names the member
     */
    static @NotNull StandingOrderDefinition decode(@NotNull StandingOrderJson.Definition body)
        throws InvalidStandingOrderException {
        StandingOrderDefinition definition = new StandingOrderDefinition(
            body.name(),
            body.enabled(),
            condition(body.trigger(), "trigger"),
            action(body.action()),
            body.cooldownSeconds());
        StandingOrderValidator.validate(definition);
        return definition;
    }

    /**
     * Applies the changes to the stored definition. An order that cannot be read has nothing to keep, so every member
     * must then be supplied.
     *
     * @param current the stored definition, or null when it cannot be read
     * @throws InvalidStandingOrderException a member that is neither supplied nor stored (MISSING_FIELD)
     */
    static @NotNull StandingOrderJson.Definition merge(@Nullable StandingOrderDefinition current,
        @NotNull StandingOrderJson.Changes changes) throws InvalidStandingOrderException {
        StandingOrderJson.Definition base = current == null ? null : encode(current);
        String name = changes.name() != null ? changes.name() : base != null ? base.name() : null;
        Boolean enabled = changes.enabled() != null ? changes.enabled() : base != null ? base.enabled() : null;
        Long cooldown = changes.cooldownSeconds() != null ? changes.cooldownSeconds()
            : base != null ? Long.valueOf(base.cooldownSeconds()) : null;
        StandingOrderJson.Condition trigger = changes.trigger() != null ? changes.trigger()
            : base != null ? base.trigger() : null;
        StandingOrderJson.Action action = changes.action() != null ? changes.action()
            : base != null ? base.action() : null;
        if (name == null) throw missing("name");
        if (enabled == null) throw missing("enabled");
        if (cooldown == null) throw missing("cooldownSeconds");
        if (trigger == null) throw missing("trigger");
        if (action == null) throw missing("action");
        return new StandingOrderJson.Definition(name, enabled, cooldown, trigger, action);
    }

    private static InvalidStandingOrderException missing(String member) {
        return new InvalidStandingOrderException(InvalidStandingOrderException.Reason.MISSING_FIELD, member);
    }

    static @NotNull StandingOrderJson.Definition encode(@NotNull StandingOrderDefinition definition) {
        return new StandingOrderJson.Definition(
            definition.name(),
            definition.enabled(),
            definition.cooldownSeconds(),
            json(definition.trigger()),
            json(definition.action()));
    }

    private static Condition condition(StandingOrderJson.Condition input, String path)
        throws InvalidStandingOrderException {
        Condition result = switch (input.type()) {
            case "stockBelow" -> new Condition.StockBelow(
                required(input.resource(), path + ".resource"),
                required(input.amount(), path + ".amount"));
            case "stockAtLeast" -> new Condition.StockAtLeast(
                required(input.resource(), path + ".resource"),
                required(input.amount(), path + ".amount"));
            case "stockRatioBelow", "stockRatioAtLeast" -> new Condition.StockComparison(
                required(input.resource(), path + ".resource"),
                ratio(input.reference(), input.numerator(), input.denominator(), path),
                input.type()
                    .equals("stockRatioAtLeast"));
            case "idlePlayerCpusAtLeast" -> new Condition.IdleCpusAtLeast(
                required(input.count(), path + ".count"),
                true);
            case "idleCpusAtLeast" -> new Condition.IdleCpusAtLeast(required(input.count(), path + ".count"));
            case "all" -> new Condition.All(conditions(required(input.children(), path + ".children"), path));
            case "any" -> new Condition.Any(conditions(required(input.children(), path + ".children"), path));
            case "not" -> new Condition.Not(condition(required(input.child(), path + ".child"), path + ".child"));
            default -> throw new InvalidStandingOrderException(Reason.UNKNOWN_KIND, path + ".type");
        };
        boolean stock = result instanceof Condition.StockBelow || result instanceof Condition.StockAtLeast;
        boolean relative = result instanceof Condition.StockComparison;
        checkRatioFields(input.reference(), input.numerator(), input.denominator(), relative, path);
        unexpected(input.resource(), stock || relative, path + ".resource");
        unexpected(input.amount(), stock, path + ".amount");
        unexpected(input.count(), result instanceof Condition.IdleCpusAtLeast, path + ".count");
        unexpected(
            input.children(),
            result instanceof Condition.All || result instanceof Condition.Any,
            path + ".children");
        unexpected(input.child(), result instanceof Condition.Not, path + ".child");
        return result;
    }

    private static List<Condition> conditions(List<StandingOrderJson.Condition> children, String path)
        throws InvalidStandingOrderException {
        List<Condition> result = new ArrayList<>(children.size());
        for (int index = 0; index < children.size(); index++) {
            result.add(condition(children.get(index), path + ".children[" + index + "]"));
        }
        return result;
    }

    private static CraftAction action(StandingOrderJson.Action input) throws InvalidStandingOrderException {
        AmountPolicy amount = amount(input.amount());
        CraftingOptions options = new CraftingOptions(
            input.options()
                .lightMode(),
            input.options()
                .ignoreMissing());
        StandingOrderJson.Cpu cpu = input.cpu();
        CpuTarget target = switch (cpu.type()) {
            case "any" -> CpuTarget.any();
            case "machineOnly" -> CpuTarget.machines();
            case "specific" -> CpuTarget.specific(required(cpu.key(), "action.cpu.key"));
            default -> throw new InvalidStandingOrderException(Reason.UNKNOWN_KIND, "action.cpu.type");
        };
        unexpected(cpu.key(), target.cpu() != null, "action.cpu.key");
        return new CraftAction(input.resource(), amount, options, target);
    }

    private static AmountPolicy amount(StandingOrderJson.Amount input) throws InvalidStandingOrderException {
        AmountPolicy result = switch (input.type()) {
            case "fixed" -> new AmountPolicy.Fixed(required(input.batch(), "action.amount.batch"));
            case "fillToRatio" -> new AmountPolicy.FillToRatio(
                ratio(input.reference(), input.numerator(), input.denominator(), "action.amount"),
                required(input.maxBatch(), "action.amount.maxBatch"));
            case "fillTo" -> new AmountPolicy.FillTo(
                required(input.target(), "action.amount.target"),
                required(input.maxBatch(), "action.amount.maxBatch"));
            default -> throw new InvalidStandingOrderException(Reason.UNKNOWN_KIND, "action.amount.type");
        };
        unexpected(input.batch(), result instanceof AmountPolicy.Fixed, "action.amount.batch");
        unexpected(input.target(), result instanceof AmountPolicy.FillTo, "action.amount.target");
        boolean relative = result instanceof AmountPolicy.FillToRatio;
        checkRatioFields(input.reference(), input.numerator(), input.denominator(), relative, "action.amount");
        unexpected(input.maxBatch(), relative || result instanceof AmountPolicy.FillTo, "action.amount.maxBatch");
        return result;
    }

    private static StockRatio ratio(@Nullable StableKey reference, @Nullable Long numerator, @Nullable Long denominator,
        String path) throws InvalidStandingOrderException {
        return new StockRatio(
            required(reference, path + ".reference"),
            required(numerator, path + ".numerator"),
            required(denominator, path + ".denominator"));
    }

    private static void checkRatioFields(@Nullable StableKey reference, @Nullable Long numerator,
        @Nullable Long denominator, boolean allowed, String path) throws InvalidStandingOrderException {
        unexpected(reference, allowed, path + ".reference");
        unexpected(numerator, allowed, path + ".numerator");
        unexpected(denominator, allowed, path + ".denominator");
    }

    private static <T> @NotNull T required(@Nullable T value, String path) throws InvalidStandingOrderException {
        if (value == null) throw missing(path);
        return value;
    }

    private static void unexpected(@Nullable Object value, boolean allowed, String path)
        throws InvalidStandingOrderException {
        if (value != null && !allowed) throw new InvalidStandingOrderException(Reason.UNKNOWN_FIELD, path);
    }

    private static StandingOrderJson.Condition json(Condition condition) {
        if (condition instanceof Condition.StockBelow below) {
            return new StandingOrderJson.Condition("stockBelow", below.resource(), below.amount(), null, null, null);
        } else if (condition instanceof Condition.StockAtLeast atLeast) {
            return new StandingOrderJson.Condition(
                "stockAtLeast",
                atLeast.resource(),
                atLeast.amount(),
                null,
                null,
                null);
        } else if (condition instanceof Condition.StockComparison compared) {
            StockRatio ratio = compared.ratio();
            return new StandingOrderJson.Condition(
                compared.atLeast() ? "stockRatioAtLeast" : "stockRatioBelow",
                compared.resource(),
                null,
                null,
                null,
                null,
                ratio.reference(),
                ratio.numerator(),
                ratio.denominator());
        } else if (condition instanceof Condition.IdleCpusAtLeast idle) {
            return new StandingOrderJson.Condition(
                idle.playerOnly() ? "idlePlayerCpusAtLeast" : "idleCpusAtLeast",
                null,
                null,
                idle.count(),
                null,
                null);
        } else if (condition instanceof Condition.All all) {
            return new StandingOrderJson.Condition("all", null, null, null, children(all.children()), null);
        } else if (condition instanceof Condition.Any any) {
            return new StandingOrderJson.Condition("any", null, null, null, children(any.children()), null);
        } else if (condition instanceof Condition.Not not) {
            return new StandingOrderJson.Condition("not", null, null, null, null, json(not.child()));
        }
        throw new IllegalStateException("Unsupported condition " + condition.getClass());
    }

    private static List<StandingOrderJson.Condition> children(List<Condition> children) {
        List<StandingOrderJson.Condition> result = new ArrayList<>(children.size());
        for (Condition child : children) result.add(json(child));
        return result;
    }

    private static StandingOrderJson.Action json(CraftAction action) {
        return new StandingOrderJson.Action(
            action.resource(),
            json(action.amount()),
            json(action.options()),
            json(action.cpu()));
    }

    private static StandingOrderJson.Amount json(AmountPolicy policy) {
        if (policy instanceof AmountPolicy.Fixed fixed) {
            return new StandingOrderJson.Amount("fixed", fixed.batch(), null, null, null, null, null);
        } else if (policy instanceof AmountPolicy.FillTo fillTo) {
            return new StandingOrderJson.Amount("fillTo", null, fillTo.target(), fillTo.maxBatch(), null, null, null);
        } else if (policy instanceof AmountPolicy.FillToRatio relative) {
            StockRatio ratio = relative.ratio();
            return new StandingOrderJson.Amount(
                "fillToRatio",
                null,
                null,
                relative.maxBatch(),
                ratio.reference(),
                ratio.numerator(),
                ratio.denominator());
        }
        throw new IllegalStateException("Unsupported amount policy " + policy.getClass());
    }

    private static StandingOrderJson.Options json(CraftingOptions options) {
        return new StandingOrderJson.Options(options.lightMode(), options.ignoreMissing());
    }

    private static StandingOrderJson.Cpu json(CpuTarget target) {
        return target.cpu() == null ? new StandingOrderJson.Cpu(target.machineOnly() ? "machineOnly" : "any", null)
            : new StandingOrderJson.Cpu("specific", target.cpu());
    }
}
