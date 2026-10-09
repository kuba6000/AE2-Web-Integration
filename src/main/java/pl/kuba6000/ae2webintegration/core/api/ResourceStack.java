package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Immutable output data captured on the server thread, safe for later asynchronous serialization. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public final class ResourceStack extends ResourceDescription {

    /**
     * Number of resource units.
     *
     * @example 64
     */
    public final long quantity;
    /**
     * Stable resource key, or null when an identity could not be captured.
     *
     * @example AAAAAAAAAAAAAAAAAAAAAA
     */
    public final @Nullable String itemKey;
    /** Detached fallback identity for tracked history; captured only when the installed pack lacks the exact icon. */
    public final transient @Nullable StableKey iconBaseKey;

    private ResourceStack(@Nullable String registryNamespace, @Nullable String registryPath, int componentCount,
        int damage, @NotNull String displayName, long quantity, @Nullable String itemKey,
        @Nullable StableKey iconBaseKey) {
        super(registryNamespace, registryPath, displayName, componentCount, damage);
        this.quantity = quantity;
        this.itemKey = itemKey;
        this.iconBaseKey = iconBaseKey;
    }

    public static @Nullable ResourceStack capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack) {
        return capture(grid, stack, null);
    }

    /** Captures history's optional fallback on the server thread before native identity ownership can expire. */
    public static @Nullable ResourceStack capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack,
        @Nullable IconPack pack) {
        if (stack == null) return null;
        return capture(grid, stack.web$what(), stack.web$amount(), pack);
    }

    /** Captures a measured resource directly, without allocating a temporary native stack. */
    public static @NotNull ResourceStack capture(@NotNull IAEGrid grid, @NotNull IAEKey key, long quantity,
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
            return new ResourceStack(
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
        return new ResourceStack(
            registryNamespace,
            registryPath,
            componentCount,
            damage,
            displayName,
            quantity,
            itemKey.toString(),
            base);
    }

}
