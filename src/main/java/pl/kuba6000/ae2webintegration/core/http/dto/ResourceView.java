package pl.kuba6000.ae2webintegration.core.http.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.ResourceDescription;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** A resource described within one HTTP response and its atlas page table. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class ResourceView extends ResourceDescription {

    /**
     * Atlas location within this response's page table; null when not requested, unavailable, or absent from the pack.
     */
    public final @Nullable IconMappings.Reference icon;

    protected ResourceView(@Nullable String registryNamespace, @Nullable String registryPath,
        @NotNull String displayName, int componentCount, int damage, @Nullable StableKey itemKey,
        @Nullable IconMappings.Reference icon) {
        super(registryNamespace, registryPath, displayName, componentCount, damage, itemKey);
        this.icon = icon;
    }
}
