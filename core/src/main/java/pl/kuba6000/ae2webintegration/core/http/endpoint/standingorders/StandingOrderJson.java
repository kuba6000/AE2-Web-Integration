package pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * JSON form of a standing order definition, shared by the request bodies and the listing. A request member that does
 * not apply to its {@code type} must be omitted; the listing reports such members as null.
 */
public final class StandingOrderJson {

    private StandingOrderJson() {}

    /**
     * The editable settings of a standing order.
     *
     * @param name            display name of 1 to 128 characters, without leading or trailing whitespace or control
     *                        characters
     * @param enabled         whether the order is evaluated
     * @param cooldownSeconds minimum seconds after a submission before the next one, from 0 to 604800
     * @param trigger         when to craft
     * @param action          what to craft
     * @example name Keep plastic
     * @example enabled true
     * @example cooldownSeconds 0
     */
    @Desugar
    public record Definition(@NotNull String name, boolean enabled, long cooldownSeconds, @NotNull Condition trigger,
        @NotNull Action action) {}

    /**
     * Changes to a standing order. Omitted members keep their stored value; a present trigger or action replaces that
     * whole part.
     *
     * @param name            replacement display name of 1 to 128 characters, without leading or trailing whitespace or
     *                        control characters
     * @param enabled         whether the order is evaluated
     * @param cooldownSeconds minimum seconds after a submission before the next one, from 0 to 604800
     * @param trigger         complete replacement trigger
     * @param action          complete replacement action
     * @example enabled false
     */
    @Desugar
    public record Changes(@OptionalInput @Nullable String name, @OptionalInput @Nullable Boolean enabled,
        @OptionalInput @Nullable Long cooldownSeconds, @OptionalInput @Nullable Condition trigger,
        @OptionalInput @Nullable Action action) {}

    /**
     * One condition of a trigger, at most 32 conditions nested at most 4 levels deep. The type selects which other
     * members apply: {@code stockBelow} and {@code stockAtLeast} use resource and amount, {@code idleCpusAtLeast}
     * uses count, {@code all} and {@code any} use children, {@code not} uses child.
     *
     * @param type     {@code stockBelow}, {@code stockAtLeast}, {@code idleCpusAtLeast}, {@code all}, {@code any} or
     *                 {@code not}
     * @param resource resource whose stored amount is compared
     * @param amount   quantity compared with the stored amount, at least 1
     * @param count    idle crafting CPUs required, from 1 to 1024; only CPUs that accept player jobs count
     * @param children conditions of a group, at least one
     * @param child    the condition a {@code not} negates
     * @example type stockBelow
     * @example amount 1000
     */
    @Desugar
    public record Condition(@NotNull String type, @OptionalInput @Nullable StableKey resource,
        @OptionalInput @Nullable Long amount, @OptionalInput @Nullable Long count,
        @OptionalInput @Nullable List<Condition> children, @OptionalInput @Nullable Condition child) {}

    /**
     * What a standing order crafts once its trigger holds.
     *
     * @param resource resource to craft; it must be craftable in the network
     * @param amount   how much to order per submission
     * @param options  calculation options; each enabled option must be supported by the server
     * @param cpu      crafting CPU selection
     */
    @Desugar
    public record Action(@NotNull StableKey resource, @NotNull Amount amount, @NotNull Options options,
        @NotNull Cpu cpu) {}

    /**
     * How much one submission orders. {@code fixed} uses batch; {@code fillTo} orders the missing quantity up to
     * target, at most maxBatch at a time.
     *
     * @param type     {@code fixed} or {@code fillTo}
     * @param batch    quantity of every submission, at least 1
     * @param target   stock to reach, at least 1
     * @param maxBatch largest single submission, at least 1
     * @example type fixed
     * @example batch 64
     */
    @Desugar
    public record Amount(@NotNull String type, @OptionalInput @Nullable Long batch,
        @OptionalInput @Nullable Long target, @OptionalInput @Nullable Long maxBatch) {}

    /**
     * Calculation options of the crafting plan, as for a manually created plan.
     *
     * @param lightMode     use Lite crafting mode; requires the craftingLightMode capability
     * @param ignoreMissing allow crafting to wait for missing ingredients; requires the craftingIgnoreMissing
     *                      capability
     */
    @Desugar
    public record Options(boolean lightMode, boolean ignoreMissing) {}

    /**
     * Which crafting CPU runs the jobs.
     *
     * @param type {@code any} lets the network choose; {@code specific} uses the CPU named by key
     * @param key  crafting CPU identifier of a {@code specific} selection
     * @example type any
     */
    @Desugar
    public record Cpu(@NotNull String type, @OptionalInput @Nullable StableKey key) {}
}
