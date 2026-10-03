package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.NativeItemIdentity;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCandidate;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Keeps the native identity intact, including dropSecondary keys with no default capabilities. */
final class IconCandidate implements IIconCandidate<IconCandidate> {

    final String context;
    private final AEKey nativeKey;

    IconCandidate(@NotNull String context, @NotNull AEKey nativeKey) {
        this.context = context;
        this.nativeKey = nativeKey;
    }

    @Override
    public @NotNull String context() {
        return context;
    }

    static @Nullable IconCandidate item(@NotNull String context, @NotNull ItemStack stack) {
        AEItemKey key = AEItemKey.of(stack);
        return key == null ? null : new IconCandidate(context, key);
    }

    @Override
    @NotNull
    public StableKey key() {
        return NativeItemIdentity.getKey(nativeKey);
    }

    @Override
    @Nullable
    public IconCandidate baseline() {
        AEKey base = nativeKey.dropSecondary();
        return nativeKey.equals(base) ? null : new IconCandidate(context + "/base", base);
    }

    @Override
    public boolean sameIdentity(@NotNull IconCandidate other) {
        return nativeKey.equals(other.nativeKey);
    }

    void render(@NotNull ModernIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws ModernIconRenderer.RenderFailure {
        if (nativeKey instanceof AEItemKey item) renderer.item(item, key, captures);
        else if (nativeKey instanceof AEFluidKey fluid) renderer.fluid(fluid, key, captures);
        else throw new IllegalStateException(
            "Unsupported discovered key " + nativeKey.getType()
                .getId());
    }
}
