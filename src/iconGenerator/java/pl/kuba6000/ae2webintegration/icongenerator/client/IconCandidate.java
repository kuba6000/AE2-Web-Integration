package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyIconBaseline;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCandidate;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** One owned native identity; the native render stack never crosses the worker boundary. */
final class IconCandidate implements IIconCandidate<IconCandidate> {

    private final String context;
    private final Object nativeStack;

    private IconCandidate(@NotNull String context, @NotNull AEItemStack nativeStack) {
        this.context = context;
        this.nativeStack = nativeStack;
    }

    private IconCandidate(@NotNull String context, @NotNull FluidStack nativeStack) {
        this.context = context;
        this.nativeStack = nativeStack;
    }

    static @Nullable IconCandidate item(@NotNull String context, @NotNull ItemStack stack) {
        if (stack.isEmpty()) return null;
        ItemStack owned = stack.copy();
        owned.setCount(1);
        AEItemStack key = AEItemStack.fromItemStack(owned);
        return key == null ? null : new IconCandidate(context, key);
    }

    static @NotNull IconCandidate fluid(@NotNull String context, @NotNull FluidStack stack) {
        return new IconCandidate(context, stack.copy());
    }

    @Override
    public @NotNull String context() {
        return context;
    }

    @Override
    public @NotNull StableKey key() {
        if (nativeStack instanceof AEItemStack item) return LegacyItemIdentity.encode(item);
        return LegacyItemIdentity.encode((FluidStack) nativeStack);
    }

    @Override
    public boolean sameIdentity(@NotNull IconCandidate other) {
        if (nativeStack instanceof AEItemStack item) return item.equals(other.nativeStack);
        if (!(other.nativeStack instanceof FluidStack otherFluid)) return false;
        FluidStack fluid = (FluidStack) nativeStack;
        // Use the same null/empty and numeric-tag equality as AE2UEL's AEFluidStack.
        return fluid.getFluid() == otherFluid.getFluid() && Platform.itemComparisons()
            .isNbtTagEqual(fluid.tag, otherFluid.tag);
    }

    @Override
    public @Nullable IconCandidate baseline() {
        if (nativeStack instanceof AEItemStack item) {
            AEItemStack baseline = LegacyIconBaseline.item(item);
            return baseline == null ? null : new IconCandidate(context + "/base", baseline);
        }
        FluidStack fluid = (FluidStack) nativeStack;
        return new IconCandidate(context + "/base", new FluidStack(fluid.getFluid(), Fluid.BUCKET_VOLUME));
    }

    void render(@NotNull LegacyIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws LegacyIconRenderer.RenderFailure {
        if (nativeStack instanceof AEItemStack item) renderer.item(item.asItemStackRepresentation(), key, captures);
        else renderer.fluid(((FluidStack) nativeStack).copy(), key, captures);
    }
}
