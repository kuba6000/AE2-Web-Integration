package pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.standingorders.AmountPolicy;
import pl.kuba6000.ae2webintegration.core.standingorders.Condition;
import pl.kuba6000.ae2webintegration.core.standingorders.CpuTarget;
import pl.kuba6000.ae2webintegration.core.standingorders.CraftAction;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderCodec;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderDefinition;

/**
 * Converts between a definition and its API form. A request is turned into the codec's stored document and decoded by
 * the codec, so a submitted definition is judged by exactly the rules and reports the same reasons and paths as a
 * stored one.
 */
final class StandingOrderMapping {

    private StandingOrderMapping() {}

    /**
     * @throws InvalidStandingOrderException the definition violates the format or a limit; the path names the member
     */
    static @NotNull StandingOrderDefinition decode(@NotNull StandingOrderJson.Definition body)
        throws InvalidStandingOrderException {
        Map<StableKey, Integer> slots = new LinkedHashMap<>();
        JsonObject document = new JsonObject();
        document.addProperty("name", body.name());
        document.addProperty("enabled", body.enabled());
        document.addProperty("cooldownSeconds", body.cooldownSeconds());
        document.add("trigger", document(body.trigger(), slots));
        document.add("action", document(body.action(), slots));
        return StandingOrderCodec.decode(
            new StandingOrderCodec.Encoded(
                StandingOrderCodec.VERSION,
                document.toString(),
                new ArrayList<>(slots.keySet())));
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

    private static int slot(Map<StableKey, Integer> slots, StableKey resource) {
        Integer existing = slots.get(resource);
        if (existing != null) return existing;
        int created = slots.size();
        slots.put(resource, created);
        return created;
    }

    private static JsonObject document(StandingOrderJson.Condition condition, Map<StableKey, Integer> slots) {
        JsonObject result = new JsonObject();
        result.addProperty("type", condition.type());
        if (condition.resource() != null) result.addProperty("resource", slot(slots, condition.resource()));
        if (condition.amount() != null) result.addProperty("amount", condition.amount());
        if (condition.count() != null) result.addProperty("count", condition.count());
        if (condition.children() != null) {
            JsonArray children = new JsonArray();
            for (StandingOrderJson.Condition child : condition.children()) children.add(document(child, slots));
            result.add("children", children);
        }
        if (condition.child() != null) result.add("child", document(condition.child(), slots));
        return result;
    }

    private static JsonObject document(StandingOrderJson.Action action, Map<StableKey, Integer> slots) {
        JsonObject result = new JsonObject();
        result.addProperty("resource", slot(slots, action.resource()));
        StandingOrderJson.Amount amount = action.amount();
        JsonObject amountDocument = new JsonObject();
        amountDocument.addProperty("type", amount.type());
        if (amount.batch() != null) amountDocument.addProperty("batch", amount.batch());
        if (amount.target() != null) amountDocument.addProperty("target", amount.target());
        if (amount.maxBatch() != null) amountDocument.addProperty("maxBatch", amount.maxBatch());
        result.add("amount", amountDocument);
        JsonObject options = new JsonObject();
        options.addProperty(
            "lightMode",
            action.options()
                .lightMode());
        options.addProperty(
            "ignoreMissing",
            action.options()
                .ignoreMissing());
        result.add("options", options);
        JsonObject cpu = new JsonObject();
        cpu.addProperty(
            "type",
            action.cpu()
                .type());
        if (action.cpu()
            .key() != null)
            cpu.addProperty(
                "key",
                action.cpu()
                    .key()
                    .toString());
        result.add("cpu", cpu);
        return result;
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
        } else if (condition instanceof Condition.IdleCpusAtLeast idle) {
            return new StandingOrderJson.Condition("idleCpusAtLeast", null, null, idle.count(), null, null);
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
            return new StandingOrderJson.Amount("fixed", fixed.batch(), null, null);
        } else if (policy instanceof AmountPolicy.FillTo fillTo) {
            return new StandingOrderJson.Amount("fillTo", null, fillTo.target(), fillTo.maxBatch());
        }
        throw new IllegalStateException("Unsupported amount policy " + policy.getClass());
    }

    private static StandingOrderJson.Options json(CraftingOptions options) {
        return new StandingOrderJson.Options(options.lightMode(), options.ignoreMissing());
    }

    private static StandingOrderJson.Cpu json(CpuTarget target) {
        return target.cpu() == null ? new StandingOrderJson.Cpu("any", null)
            : new StandingOrderJson.Cpu("specific", target.cpu());
    }
}
