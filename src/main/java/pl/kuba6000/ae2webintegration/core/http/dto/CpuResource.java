package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.cpu.CpuObservation;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Current crafting CPU quantities and optional tracking measurements for one resource.
 * Tracking measurements remain zero when this CPU has no tracked job.
 */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class CpuResource extends ResourceDescription {

    /**
     * Resource units currently being processed.
     *
     * @example 16
     */
    public long active = 0;
    /**
     * Resource units waiting to be processed.
     *
     * @example 16
     */
    public long pending = 0;
    /**
     * Resource units held by the crafting CPU.
     *
     * @example 32
     */
    public long stored = 0;
    /**
     * Measured time spent crafting this resource, in milliseconds.
     *
     * @example 5000
     */
    public long timeSpentCrafting = 0;
    /**
     * Whether the tracked job is waiting for this resource now, so that {@code timeSpentCrafting} is still growing.
     *
     * @example true
     */
    public boolean crafting = false;
    /**
     * Total resource units produced during the measured work.
     *
     * @example 32
     */
    public long craftedTotal = 0;
    /**
     * Fraction of summed resource processing time attributed to this resource; one when a tracked job has no
     * processing time recorded, or zero when tracking is unavailable.
     *
     * @example 1.0
     */
    public double shareInCraftingTime = 0d;
    /**
     * Fraction of job elapsed time spent processing this resource, capped at one.
     *
     * @example 0.5
     */
    public double shareInCraftingTimeCombined = 0d;
    /**
     * Produced resource units per second of measured processing time.
     *
     * @example 6.4
     */
    public double craftsPerSec = 0d;

    public CpuResource(@NotNull ResourceDescriptor description, @NotNull CpuObservation.Amounts amounts, long at,
        @Nullable IconMappings mappings) {
        super(description, mappings);
        active = amounts.active();
        pending = amounts.pending();
        stored = amounts.stored();
        timeSpentCrafting = amounts.timeSpent(at);
        craftedTotal = amounts.craftedTotal();
        crafting = amounts.crafting();
    }

}
