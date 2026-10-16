package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Immutable resource description, detached from native state and independent of amounts. */
@Desugar
public record ResourceDescriptor(@Nullable String registryNamespace, @Nullable String registryPath, int componentCount,
    int damage, @NotNull String displayName, @NotNull ResourceType resourceType, @Nullable StableKey itemKey,
    @Nullable StableKey iconBaseKey) {

    /** Read only on the native server thread. Unsupported identities remain displayable without a key. */
    public static @NotNull ResourceDescriptor capture(@NotNull IAEKey key, @Nullable StableKey itemKey,
        @Nullable StableKey iconBaseKey) {
        return new ResourceDescriptor(
            key.web$getRegistryNamespace(),
            key.web$getRegistryPath(),
            key.web$getComponentCount(),
            key.web$getDamage(),
            key.web$getDisplayName(),
            key.web$getResourceType(),
            itemKey,
            iconBaseKey);
    }
}
