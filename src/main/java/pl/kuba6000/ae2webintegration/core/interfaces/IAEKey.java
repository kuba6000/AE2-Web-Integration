package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Native resource key: equals/hashCode identify the exact variant independently of quantity or crafting state. */
public interface IAEKey {

    /** Cheap type capability check; true does not guarantee that every variant has an encodable identity. */
    default boolean web$supportsStableIdentity() {
        return true;
    }

    /** Server-thread-only category from the native resource, without constructing a display stack. */
    @NotNull
    ResourceType web$getResourceType();

    /** Canonical identity, or null for a known unsupported type/variant; calculation failures still propagate. */
    @Nullable
    StableKey web$getKey();

    /** Preserves resource identity for retention; quantity/crafting state may be reset and read-only data shared. */
    @NotNull
    IAEKey web$copyIdentity();

    /** Server-thread-only base variant identity; null when unsupported. Does not replace exact resource identity. */
    default @Nullable StableKey web$getBaseKey() {
        return null;
    }

    /** Native registry namespace, or null when this resource has no registry entry. */
    @Nullable
    String web$getRegistryNamespace();

    /** Native registry path, without damage or variant suffixes; null when unavailable. */
    @Nullable
    String web$getRegistryPath();

    /**
     * Root NBT entry count on 1.7.10/1.12.2/1.20.1; effective component count on 1.21.1. Zero when absent or
     * unsupported.
     */
    int web$getComponentCount();

    /** Raw legacy item damage/metadata or modern damage value; zero for fluids and unsupported resources. */
    int web$getDamage();

    @NotNull
    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

}
