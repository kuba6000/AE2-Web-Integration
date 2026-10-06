package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Native resource key: equals/hashCode identify the exact variant independently of quantity or crafting state. */
public interface IAEKey {

    /** Server-thread-only category from the native resource, without constructing a display stack. */
    @NotNull
    ResourceType web$getResourceType();

    /** Stable identity from canonical native data, independent of quantity or craftability. */
    @NotNull
    StableKey web$getKey();

    /** Preserves resource identity for retention; quantity/crafting state may be reset and read-only data shared. */
    @NotNull
    IAEKey web$copyIdentity();

    /** Server-thread-only normalized native identity for icon fallback; null when unsupported. */
    default @Nullable StableKey web$getIconBaseKey() {
        return null;
    }

    /** Native registry namespace, or null when this resource has no registry entry. */
    @Nullable
    String web$getRegistryNamespace();

    /** Native registry path, without damage or variant suffixes; null when unavailable. */
    @Nullable
    String web$getRegistryPath();

    /** Root NBT entry count on 1.7.10/1.12.2/1.20.1; effective component count on 1.21.1. Null when unsupported. */
    @Nullable
    Integer web$getComponentCount();

    /** Raw legacy item damage/metadata or modern damage value; null for fluids and unsupported resources. */
    @Nullable
    Integer web$getDamage();

    @NotNull
    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

}
