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

    @NotNull
    String web$getItemID();

    @NotNull
    String web$getDisplayName();

    boolean web$isCraftable(IAEGrid grid);

}
