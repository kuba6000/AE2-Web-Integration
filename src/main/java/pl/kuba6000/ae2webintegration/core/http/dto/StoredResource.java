package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.ResourceDescription;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;

/** A stored or craftable resource with its currently available web identity. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public final class StoredResource extends ResourceDescription {

    /**
     * Native resource category for item and fluid filtering.
     *
     * @example ITEM
     */
    public final @NotNull ResourceType resourceType;
    /**
     * Number of resource units.
     *
     * @example 64
     */
    public final long quantity;
    /**
     * Whether the resource is craftable on this grid.
     *
     * @example true
     */
    public final boolean craftable;
    /**
     * Stable resource key, or null when an identity could not be captured.
     *
     * @example AAAAAAAAAAAAAAAAAAAAAA
     */
    public final @Nullable String itemKey;
    /**
     * Identity failure code AMBIGUOUS, UNSUPPORTED or UNAVAILABLE; null when the resource key is available.
     *
     * @example null
     */
    public final @Nullable String identityStatus;
    /** Atlas location within the response's page table; null when icons were not requested or unavailable. */
    public final @Nullable IconMappings.Reference icon;

    public StoredResource(@Nullable String registryNamespace, @Nullable String registryPath,
        @NotNull String displayName, int componentCount, int damage, @NotNull ResourceType resourceType, long quantity,
        boolean craftable, @Nullable String itemKey, @Nullable String identityStatus,
        @Nullable IconMappings.Reference icon) {
        super(registryNamespace, registryPath, displayName, componentCount, damage);
        this.resourceType = resourceType;
        this.quantity = quantity;
        this.craftable = craftable;
        this.itemKey = itemKey;
        this.identityStatus = identityStatus;
        this.icon = icon;
    }
}
