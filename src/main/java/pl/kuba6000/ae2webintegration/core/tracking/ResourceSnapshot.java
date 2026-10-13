package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Detached resource data retained by tracking and crafting plans, captured only on the server thread. */
@Desugar
public record ResourceSnapshot(@Nullable String registryNamespace, @Nullable String registryPath, int componentCount,
    int damage, @NotNull String displayName, @NotNull ResourceType resourceType, long quantity,
    @Nullable StableKey itemKey, @Nullable StableKey iconBaseKey) {

    /** Captures history's optional fallback on the server thread before native identity ownership can expire. */
    public static @Nullable ResourceSnapshot capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack,
        @Nullable IconPack pack) {
        if (stack == null) return null;
        return capture(grid, stack.web$what(), stack.web$amount(), pack);
    }

    /** Captures a measured resource directly, without allocating a temporary native stack. */
    public static @NotNull ResourceSnapshot capture(@NotNull IAEGrid grid, @NotNull IAEKey key, long quantity,
        @Nullable IconPack pack) {
        StableKey itemKey;
        try {
            itemKey = AE2Controller.itemIdentities.remember(grid, key);
        } catch (RuntimeException exception) {
            itemKey = null;
        }
        return capture(key, quantity, itemKey, pack);
    }

    /** Captures a resource with an already known identity, preserving it even if optional icon fallback fails. */
    public static @NotNull ResourceSnapshot capture(@NotNull IAEKey key, long quantity, @Nullable StableKey itemKey,
        @Nullable IconPack pack) {
        StableKey base = null;
        if (itemKey != null && pack != null && pack.find(itemKey) == null) {
            try {
                base = AE2Controller.itemIdentities.resolveIconBase(itemKey);
            } catch (RuntimeException ignored) {
                // Optional fallback must not discard a successfully captured exact identity.
            }
        }
        return new ResourceSnapshot(
            key.web$getRegistryNamespace(),
            key.web$getRegistryPath(),
            key.web$getComponentCount(),
            key.web$getDamage(),
            key.web$getDisplayName(),
            key.web$getResourceType(),
            quantity,
            itemKey,
            base);
    }

}
