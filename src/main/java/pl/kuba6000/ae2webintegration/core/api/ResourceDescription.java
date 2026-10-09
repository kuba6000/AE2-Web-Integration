package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Shared resource metadata captured from native data on the server thread. */
@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class ResourceDescription {

    /**
     * Native registry path without namespace or damage; null when unavailable.
     *
     * @example iron_ingot
     */
    public final @Nullable String registryPath;
    /** Native registry namespace, or null when unavailable. */
    public final @Nullable String registryNamespace;
    /**
     * Root NBT entry count on 1.7.10/1.12.2/1.20.1; effective component count on 1.21.1. Zero when absent or
     * unsupported.
     */
    public final int componentCount;
    /** Raw legacy item damage/metadata or modern damage value; zero for fluids and unsupported resources. */
    public final int damage;
    /**
     * Resource display name.
     *
     * @example Iron Ingot
     */
    public final @NotNull String displayName;

    /**
     * Stable resource key, or null when an identity could not be captured.
     *
     * @example AAAAAAAAAAAAAAAAAAAAAA
     */
    public final @Nullable StableKey itemKey;

    protected ResourceDescription(@Nullable String registryNamespace, @Nullable String registryPath,
        @NotNull String displayName, int componentCount, int damage, @Nullable StableKey itemKey) {
        this.registryNamespace = registryNamespace;
        this.registryPath = registryPath;
        this.displayName = displayName;
        this.componentCount = componentCount;
        this.damage = damage;
        this.itemKey = itemKey;
    }
}
