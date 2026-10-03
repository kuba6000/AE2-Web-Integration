package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.core.HolderLookup;
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

/** Keeps the native identity intact, including component patches and native dropSecondary semantics. */
final class IconCandidate implements IIconCandidate<IconCandidate> {

    final String context;
    private final AEKey nativeKey;
    private final HolderLookup.Provider registries;

    IconCandidate(@NotNull String context, @NotNull AEKey nativeKey, @NotNull HolderLookup.Provider registries) {
        this.context = context;
        this.nativeKey = nativeKey;
        this.registries = registries;
    }

    @Override
    public @NotNull String context() {
        return context;
    }

    static @Nullable IconCandidate item(@NotNull String context, @NotNull ItemStack stack,
        @NotNull HolderLookup.Provider registries) {
        AEItemKey key = AEItemKey.of(stack);
        return key == null ? null : new IconCandidate(context, key, registries);
    }

    @Override
    @NotNull
    public StableKey key() {
        return NativeItemIdentity.getKey(nativeKey, registries);
    }

    @Override
    @Nullable
    public IconCandidate baseline() {
        AEKey base = nativeKey.dropSecondary();
        return nativeKey.equals(base) ? null : new IconCandidate(context + "/base", base, registries);
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
