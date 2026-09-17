package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.Nullable;

/** A stored or craftable resource with its currently available web identity. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class JSON_DetailedItem {

    /**
     * Registry resource identifier.
     *
     * @example minecraft:iron_ingot
     */
    public String itemid;
    /**
     * Resource display name.
     *
     * @example Iron Ingot
     */
    public String itemname;
    /**
     * Number of resource units.
     *
     * @example 64
     */
    public long quantity;
    /**
     * Whether the resource is craftable on this grid.
     *
     * @example true
     */
    public boolean craftable;
    /**
     * Stable resource key, or null when an identity could not be captured.
     *
     * @example AAAAAAAAAAAAAAAAAAAAAA
     */
    public @Nullable String itemKey;
    /**
     * Identity failure code AMBIGUOUS, UNSUPPORTED or UNAVAILABLE; null when the resource key is available.
     *
     * @example null
     */
    public @Nullable String identityStatus;
}
