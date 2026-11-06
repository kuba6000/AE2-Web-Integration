package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** A history resource count encoded exactly, including values above JavaScript's safe integer range. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public final class TrackedResource extends ResourceDescription {

    /** Exact signed decimal resource count. */
    public final @NotNull String quantity;

    public TrackedResource(@NotNull ResourceDescriptor resource, long quantity, @Nullable IconMappings.Reference icon) {
        super(
            resource.registryNamespace(),
            resource.registryPath(),
            resource.displayName(),
            resource.componentCount(),
            resource.damage(),
            resource.resourceType(),
            resource.itemKey(),
            icon);
        this.quantity = Long.toString(quantity);
    }
}
