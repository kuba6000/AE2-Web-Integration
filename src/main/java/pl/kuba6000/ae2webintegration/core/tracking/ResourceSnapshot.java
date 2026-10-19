package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Detached amount retained by crafting plans and history. Its immutable description is shared. */
@Desugar
public record ResourceSnapshot(@NotNull ResourceDescriptor description, long quantity) {

    public static @Nullable ResourceSnapshot capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack) {
        if (stack == null) return null;
        return capture(grid, stack.web$what(), stack.web$amount());
    }

    public static @NotNull ResourceSnapshot capture(@NotNull IAEGrid grid, @NotNull IAEKey key, long quantity) {
        StableKey itemKey;
        try {
            itemKey = AE2Controller.itemIdentities.remember(grid, key);
        } catch (RuntimeException exception) {
            itemKey = null;
        }
        return capture(key, quantity, itemKey);
    }

    public static @NotNull ResourceSnapshot capture(@NotNull IAEKey key, long quantity, @Nullable StableKey itemKey) {
        return new ResourceSnapshot(describe(key, itemKey), quantity);
    }

    /** The shared retained description of a keyed resource, or a detached capture of an unkeyed or conflicted one. */
    public static @NotNull ResourceDescriptor describe(@NotNull IAEKey key, @Nullable StableKey itemKey) {
        ResourceDescriptor description = null;
        if (itemKey != null) {
            try {
                description = AE2Controller.itemIdentities.describe(itemKey);
            } catch (ItemIdentityRegistry.Ambiguous unavailable) {
                // Preserve the captured identity when description lookup discovers a conflict.
            }
        }
        if (description == null) {
            StableKey baseKey = null;
            try {
                if (itemKey != null) baseKey = key.web$getBaseKey();
            } catch (RuntimeException unavailable) {
                // Optional normalization must not prevent displaying an unregistered resource.
            }
            description = ResourceDescriptor.capture(key, itemKey, baseKey);
        }
        return description;
    }
}
