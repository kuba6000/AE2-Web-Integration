package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Resource identity, display information and optional icon. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class ResourceDescription {

    /**
     * Resource category, such as an item or fluid.
     *
     * @example ITEM
     */
    public final @NotNull ResourceType resourceType;
    /**
     * Native registry path without namespace or damage; null when unavailable.
     *
     * @example iron_ingot
     */
    public final @Nullable String registryPath;
    /** Native registry namespace, or null when unavailable. */
    public final @Nullable String registryNamespace;
    /**
     * Root NBT entry count on 1.7.10/1.12.2/1.20.1; effective component count on 1.21.1. Zero when absent or
     * unsupported.
     */
    public final int componentCount;
    /** Raw legacy item damage/metadata or modern damage value; zero for fluids and unsupported resources. */
    public final int damage;
    /**
     * Resource display name.
     *
     * @example Iron Ingot
     */
    public final @NotNull String displayName;

    /**
     * Stable resource key, or null when a stable identity is unavailable.
     *
     * @example AAAAAAAAAAAAAAAAAAAAAA
     */
    public final @Nullable StableKey itemKey;

    /**
     * Static atlas location and optional animation reference within this response's icon tables;
     * null when not requested, unavailable, or absent from the pack.
     */
    public final @Nullable IconMappings.Reference icon;

    protected ResourceDescription(@Nullable String registryNamespace, @Nullable String registryPath,
        @NotNull String displayName, int componentCount, int damage, @NotNull ResourceType resourceType,
        @Nullable StableKey itemKey, @Nullable IconMappings.Reference icon) {
        this.registryNamespace = registryNamespace;
        this.registryPath = registryPath;
        this.displayName = displayName;
        this.componentCount = componentCount;
        this.damage = damage;
        this.resourceType = resourceType;
        this.itemKey = itemKey;
        this.icon = icon;
    }

    protected ResourceDescription(@NotNull ResourceDescriptor descriptor, @Nullable IconMappings mappings) {
        this(
            descriptor.registryNamespace(),
            descriptor.registryPath(),
            descriptor.displayName(),
            descriptor.componentCount(),
            descriptor.damage(),
            descriptor.resourceType(),
            descriptor.itemKey(),
            mappings == null || descriptor.itemKey() == null ? null
                : mappings.resolve(descriptor.itemKey(), descriptor.baseKey()));
    }
}
