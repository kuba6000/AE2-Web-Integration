package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Detached resource data retained by tracking, captured only on the server thread. */
@Desugar
public record ResourceSnapshot(@Nullable String registryNamespace, @Nullable String registryPath, int componentCount,
    int damage, @NotNull String displayName, long quantity, @Nullable StableKey itemKey,
    @Nullable StableKey iconBaseKey) {

    /** Captures history's optional fallback on the server thread before native identity ownership can expire. */
    public static @Nullable ResourceSnapshot capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack,
        @Nullable IconPack pack) {
        if (stack == null) return null;
        return capture(grid, stack.web$what(), stack.web$amount(), pack);
    }

    /** Captures a measured resource directly, without allocating a temporary native stack. */
    public static @NotNull ResourceSnapshot capture(@NotNull IAEGrid grid, @NotNull IAEKey key, long quantity,
        @Nullable IconPack pack) {
        String registryNamespace = key.web$getRegistryNamespace();
        String registryPath = key.web$getRegistryPath();
        int componentCount = key.web$getComponentCount();
        int damage = key.web$getDamage();
        String displayName = key.web$getDisplayName();
        StableKey itemKey;
        try {
            itemKey = AE2Controller.itemIdentities.remember(grid, key);
        } catch (RuntimeException exception) {
            return new ResourceSnapshot(
                registryNamespace,
                registryPath,
                componentCount,
                damage,
                displayName,
                quantity,
                null,
                null);
        }
        StableKey base = null;
        if (pack != null && pack.find(itemKey) == null) {
            try {
                base = AE2Controller.itemIdentities.resolveIconBase(itemKey);
            } catch (RuntimeException ignored) {
                // Optional fallback must not discard a successfully captured exact identity.
            }
        }
        return new ResourceSnapshot(
            registryNamespace,
            registryPath,
            componentCount,
            damage,
            displayName,
            quantity,
            itemKey,
            base);
    }

}
