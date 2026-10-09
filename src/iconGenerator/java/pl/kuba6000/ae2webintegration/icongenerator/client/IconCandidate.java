package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.ae2interface.implementations.NativeItemIdentity;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCandidate;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Owns the discovered native stack through hashing, collision checks and rendering. */
final class IconCandidate implements IIconCandidate<IconCandidate> {

    final String context;
    private final ItemStack item;
    private final FluidStack fluid;
    private final HolderLookup.Provider registries;

    private IconCandidate(@NotNull String context, @NotNull ItemStack item, @NotNull FluidStack fluid,
        @NotNull HolderLookup.Provider registries) {
        this.context = context;
        this.item = item;
        this.fluid = fluid;
        this.registries = registries;
    }

    @Override
    public @NotNull String context() {
        return context;
    }

    static @Nullable IconCandidate item(@NotNull String context, @NotNull ItemStack stack,
        @NotNull HolderLookup.Provider registries) {
        return stack.isEmpty() ? null
            : new IconCandidate(context, stack.copyWithCount(1), FluidStack.EMPTY, registries);
    }

    /** Takes ownership of the one-unit stack created by the native/EMI catalogue. */
    static @Nullable IconCandidate fluid(@NotNull String context, @NotNull FluidStack stack,
        @NotNull HolderLookup.Provider registries) {
        return stack.isEmpty() ? null : new IconCandidate(context, ItemStack.EMPTY, stack, registries);
    }

    @Override
    public @NotNull StableKey key() {
        return item.isEmpty() ? NativeItemIdentity.getKey(fluid, registries)
            : NativeItemIdentity.getKey(item, registries);
    }

    @Override
    public @Nullable IconCandidate baseline() {
        if (!item.isEmpty()) {
            ItemStack base = item.getItem()
                .getDefaultInstance();
            return ItemStack.isSameItemSameComponents(item, base) ? null
                : new IconCandidate(context + "/base", base, FluidStack.EMPTY, registries);
        }
        FluidStack base = new FluidStack(fluid.getFluid(), 1);
        return FluidStack.isSameFluidSameComponents(fluid, base) ? null
            : new IconCandidate(context + "/base", ItemStack.EMPTY, base, registries);
    }

    @Override
    public boolean sameIdentity(@NotNull IconCandidate other) {
        return ItemStack.isSameItemSameComponents(item, other.item)
            && FluidStack.isSameFluidSameComponents(fluid, other.fluid);
    }

    void render(@NotNull ModernIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws ModernIconRenderer.RenderFailure {
        // Mod renderers may mutate their input; retain the owned stack for collision comparisons.
        if (!item.isEmpty()) renderer.item(item.copy(), key, captures);
        else renderer.fluid(fluid.copy(), key, captures);
    }
}
