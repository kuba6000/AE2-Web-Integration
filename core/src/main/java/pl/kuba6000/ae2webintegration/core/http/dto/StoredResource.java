package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** A resource available in network storage or through crafting. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public final class StoredResource extends ResourceStack {

    /**
     * Whether the resource can be crafted in this network.
     *
     * @example true
     */
    public final boolean craftable;

    public StoredResource(@Nullable String registryNamespace, @Nullable String registryPath,
        @NotNull String displayName, int componentCount, int damage, @NotNull ResourceType resourceType, long quantity,
        boolean craftable, @Nullable StableKey itemKey, @Nullable IconMappings.Reference icon) {
        super(
            registryNamespace,
            registryPath,
            displayName,
            componentCount,
            damage,
            resourceType,
            quantity,
            itemKey,
            icon);
        this.craftable = craftable;
    }

    public StoredResource(@NotNull ResourceDescriptor descriptor, long quantity, boolean craftable,
        @Nullable IconMappings mappings) {
        super(descriptor, quantity, mappings);
        this.craftable = craftable;
    }
}
