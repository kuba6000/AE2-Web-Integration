package pl.kuba6000.ae2webintegration.core.tracking;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Detached amount retained by crafting plans and history. Its immutable description is shared. */
@Desugar
public record ResourceSnapshot(@NotNull ResourceDescriptor description, long quantity) {

    public static @Nullable ResourceSnapshot capture(@NotNull IAEGrid grid, @Nullable IAEGenericStack stack,
        @Nullable IconPack pack) {
        if (stack == null) return null;
        return capture(grid, stack.web$what(), stack.web$amount(), pack);
    }

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

    public static @NotNull ResourceSnapshot capture(@NotNull IAEKey key, long quantity, @Nullable StableKey itemKey,
        @Nullable IconPack pack) {
        ResourceDescriptor description = null;
        if (itemKey != null) {
            try {
                description = AE2Controller.itemIdentities.describe(itemKey, pack);
            } catch (ItemIdentityRegistry.Ambiguous unavailable) {
                // Preserve the captured identity when optional presentation lookup discovers a conflict.
            }
        }
        if (description == null) description = ResourceDescriptor.capture(key, itemKey, null);
        return new ResourceSnapshot(description, quantity);
    }
}
