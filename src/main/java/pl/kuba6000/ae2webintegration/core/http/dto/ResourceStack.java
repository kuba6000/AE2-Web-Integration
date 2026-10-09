package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** A crafting product with a response-local icon, detached from the retained tracking snapshot. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public final class ResourceStack extends ResourceDescription {

    /**
     * Number of resource units.
     *
     * @example 64
     */
    public final long quantity;

    public ResourceStack(@NotNull ResourceSnapshot snapshot, @Nullable IconMappings mappings) {
        super(
            snapshot.registryNamespace(),
            snapshot.registryPath(),
            snapshot.displayName(),
            snapshot.componentCount(),
            snapshot.damage(),
            snapshot.itemKey(),
            mappings == null || snapshot.itemKey() == null ? null
                : mappings.resolve(snapshot.itemKey(), snapshot.iconBaseKey()));
        this.quantity = snapshot.quantity();
    }
}
