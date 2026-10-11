package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** A resource quantity with a response-local icon, independent of grid craftability. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class ResourceStack extends ResourceDescription {

    /**
     * Number of resource units.
     *
     * @example 64
     */
    public final long quantity;

    protected ResourceStack(@Nullable String registryNamespace, @Nullable String registryPath,
        @NotNull String displayName, int componentCount, int damage, @NotNull ResourceType resourceType, long quantity,
        @Nullable StableKey itemKey, @Nullable IconMappings.Reference icon) {
        super(registryNamespace, registryPath, displayName, componentCount, damage, resourceType, itemKey, icon);
        this.quantity = quantity;
    }

    public ResourceStack(@NotNull ResourceSnapshot snapshot, @Nullable IconMappings mappings) {
        this(
            snapshot.registryNamespace(),
            snapshot.registryPath(),
            snapshot.displayName(),
            snapshot.componentCount(),
            snapshot.damage(),
            snapshot.resourceType(),
            snapshot.quantity(),
            snapshot.itemKey(),
            mappings == null || snapshot.itemKey() == null ? null
                : mappings.resolve(snapshot.itemKey(), snapshot.iconBaseKey()));
    }
}
