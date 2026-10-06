package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/**
 * Current crafting CPU quantities and optional tracking measurements for one resource.
 * Tracking measurements remain zero when this CPU has no tracked job.
 */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class CpuResource {

    /** Exact resource identity, or null when the native identity cannot be captured. */
    public @Nullable String itemKey;
    /** Atlas location within the response's page table; null when unavailable. */
    public @Nullable IconMappings.Reference icon;

    /**
     * Native registry path without namespace or damage; null when unavailable.
     *
     * @example iron_ingot
     */
    public final @Nullable String registryPath;
    /** Native registry namespace, or null when unavailable. */
    public final @Nullable String registryNamespace;
    /** Root NBT entry count on 1.7.10/1.12.2/1.20.1; effective component count on 1.21.1. Null when unsupported. */
    public final @Nullable Integer componentCount;
    /** Raw legacy item damage/metadata or modern damage value; null for fluids and unsupported resources. */
    public final @Nullable Integer damage;
    /**
     * Resource display name.
     *
     * @example Iron Ingot
     */
    public final @NotNull String displayName;
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

    public CpuResource(IAEKey key) {
        this.registryNamespace = key.web$getRegistryNamespace();
        this.registryPath = key.web$getRegistryPath();
        this.componentCount = key.web$getComponentCount();
        this.damage = key.web$getDamage();
        this.displayName = key.web$getDisplayName();
    }

}
