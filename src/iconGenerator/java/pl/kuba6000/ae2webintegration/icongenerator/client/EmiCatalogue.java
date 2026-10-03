package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiReloadManager;

/** Optional bridge; loaded only after EMI presence is checked. Readiness is pinned to EMI 1.1.13. */
final class EmiCatalogue {

    private final List<EmiStack> stacks;

    private EmiCatalogue(@NotNull List<EmiStack> stacks) {
        this.stacks = stacks;
    }

    static @Nullable EmiCatalogue ready() {
        if (!EmiReloadManager.isLoaded()) return null;
        List<EmiStack> stacks = EmiApi.getIndexStacks();
        if (!EmiReloadManager.isLoaded() || stacks != EmiApi.getIndexStacks()) return null;
        // Completed EMI reload publishes an immutable list. Retain that generation, never the live mutable builder.
        return new EmiCatalogue(stacks);
    }

    static boolean failed() {
        return EmiReloadManager.getStatus() == -1;
    }

    void verify() {
        if (!EmiReloadManager.isLoaded() || stacks != EmiApi.getIndexStacks()) throw new Changed();
    }

    int size() {
        return stacks.size();
    }

    @Nullable
    IconCandidate candidate(int index) {
        EmiStack stack = stacks.get(index);
        if (stack.isEmpty()) return null;
        String context = "emi/" + index;
        Object key = stack.getKey();
        if (key instanceof Item) return IconCandidate.item(context, stack.getItemStack());
        if (key instanceof Fluid fluid) return new IconCandidate(context, AEFluidKey.of(fluid, stack.getNbt()));
        throw new UnsupportedOperationException(
            "Unsupported EMI ingredient kind " + key.getClass()
                .getName());
    }

    static final class Changed extends IllegalStateException {

        Changed() {
            super("EMI catalogue changed during export; start a new export after reload completes");
        }
    }
}
