package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.storage.data.IAEStack;
import appeng.fluids.util.AEFluidStack;
import appeng.util.item.AEItemStack;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyIconBaseline;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCandidate;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** One owned native identity; the native render stack never crosses the worker boundary. */
final class IconCandidate implements IIconCandidate<IconCandidate> {

    private final String context;
    private final IAEStack<?> nativeStack;

    private IconCandidate(@NotNull String context, @NotNull IAEStack<?> nativeStack) {
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

    static @Nullable IconCandidate fluid(@NotNull String context, @NotNull FluidStack stack) {
        AEFluidStack key = AEFluidStack.fromFluidStack(stack.copy());
        return key == null ? null : new IconCandidate(context, key);
    }

    @Override
    public @NotNull String context() {
        return context;
    }

    @Override
    public @NotNull StableKey key() {
        return LegacyItemIdentity.encode(nativeStack);
    }

    @Override
    public boolean sameIdentity(@NotNull IconCandidate other) {
        return nativeStack.equals(other.nativeStack);
    }

    @Override
    public @Nullable IconCandidate baseline() {
        IAEStack<?> baseline;
        if (nativeStack instanceof AEItemStack item) baseline = LegacyIconBaseline.item(item);
        else baseline = LegacyIconBaseline.fluid((AEFluidStack) nativeStack);
        return baseline == null ? null : new IconCandidate(context + "/base", baseline);
    }

    void render(@NotNull LegacyIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws LegacyIconRenderer.RenderFailure {
        if (nativeStack instanceof AEItemStack item) renderer.item(item.asItemStackRepresentation(), key, captures);
        else renderer.fluid(((AEFluidStack) nativeStack).getFluidStack(), key, captures);
    }
}
