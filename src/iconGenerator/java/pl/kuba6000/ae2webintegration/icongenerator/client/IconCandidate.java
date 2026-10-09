package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;
import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.ae2interface.implementations.NativeItemIdentity;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCandidate;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Owns render stacks; base identity excludes defaults that render stacks may initialize. */
final class IconCandidate implements IIconCandidate<IconCandidate> {

    final String context;
    private final ItemStack item;
    private final FluidStack fluid;
    private final boolean base;
    private final @Nullable CompoundTag caps;

    private IconCandidate(@NotNull String context, @NotNull ItemStack item, @NotNull FluidStack fluid, boolean base,
        @Nullable CompoundTag caps) {
        this.context = context;
        this.item = item;
        this.fluid = fluid;
        this.base = base;
        this.caps = caps;
    }

    IconCandidate(@NotNull String context, @NotNull FluidStack fluid) {
        this(context, ItemStack.EMPTY, fluid, false, null);
    }

    @Override
    public @NotNull String context() {
        return context;
    }

    static @Nullable IconCandidate item(@NotNull String context, @NotNull ItemStack stack) {
        if (stack.isEmpty()) return null;
        CompoundTag caps = (CompoundTag) stack.serializeNBT()
            .get("ForgeCaps");
        // Snapshot before copying: capability providers may normalize data during deserialization.
        if (caps != null) caps = caps.copy();
        ItemStack owned = stack.copy();
        owned.setCount(1);
        return new IconCandidate(context, owned, FluidStack.EMPTY, false, caps);
    }

    @Override
    public @NotNull StableKey key() {
        if (!item.isEmpty()) return base ? NativeItemIdentity.getBaseKey(item) : NativeItemIdentity.getKey(item, caps);
        return base ? NativeItemIdentity.getBaseKey(fluid) : NativeItemIdentity.getKey(fluid);
    }

    @Override
    public @Nullable IconCandidate baseline() {
        if (base) return null;
        if (!item.isEmpty()) {
            if (item.getTag() == null && caps == null) return null;
            ItemStack render = new ItemStack(item.getItem());
            render.setTag(null);
            return new IconCandidate(context + "/base", render, FluidStack.EMPTY, true, null);
        }
        return fluid.getTag() == null ? null
            : new IconCandidate(context + "/base", ItemStack.EMPTY, new FluidStack(fluid.getFluid(), 1), true, null);
    }

    @Override
    public boolean sameIdentity(@NotNull IconCandidate other) {
        if (!item.isEmpty()) {
            return !other.item.isEmpty() && item.getItem() == other.item.getItem()
                && Objects.equals(base ? null : item.getTag(), other.base ? null : other.item.getTag())
                && Objects.equals(caps, other.caps);
        }
        return other.item.isEmpty() && fluid.getFluid() == other.fluid.getFluid()
            && Objects.equals(base ? null : fluid.getTag(), other.base ? null : other.fluid.getTag());
    }

    void render(@NotNull ModernIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws ModernIconRenderer.RenderFailure {
        // Custom renderers receive a disposable stack, keeping retained collision identity stable.
        if (!item.isEmpty()) renderer.item(item.copy(), key, captures);
        else renderer.fluid(fluid.copy(), key, captures);
    }
}
