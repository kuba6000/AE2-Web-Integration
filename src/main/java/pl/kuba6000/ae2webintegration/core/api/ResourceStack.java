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
public final class ResourceStack {

    /**
     * Native registry path without namespace or damage; null when unavailable.
     *
     * @example iron_ingot
     */
    public final @Nullable String registryPath;
    /** Native registry namespace, or null when unavailable. */
    public final @Nullable String registryNamespace;
    /** Root NBT entry count on 1.7.10/1.12.2/1.20.1; effective component count on 1.21.1. Null when unsupported. */
    public final @Nullable Integer componentCount;
    /** Raw legacy item damage/metadata or modern damage value; null for fluids and unsupported resources. */
    public final @Nullable Integer damage;
    /**
     * Resource display name.
     *
     * @example Iron Ingot
     */
    public final @NotNull String displayName;
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

    private ResourceStack(@Nullable String registryNamespace, @Nullable String registryPath,
        @Nullable Integer componentCount, @Nullable Integer damage, @NotNull String displayName, long quantity,
        @Nullable String itemKey, @Nullable StableKey iconBaseKey) {
        this.registryNamespace = registryNamespace;
        this.registryPath = registryPath;
        this.componentCount = componentCount;
        this.damage = damage;
        this.displayName = displayName;
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
        IAEKey key = stack.web$what();
        String registryNamespace = key.web$getRegistryNamespace();
        String registryPath = key.web$getRegistryPath();
        Integer componentCount = key.web$getComponentCount();
        Integer damage = key.web$getDamage();
        String displayName = key.web$getDisplayName();
        long quantity = stack.web$amount();
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
