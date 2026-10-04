package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;
import java.util.Objects;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyIconBaseline;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.icons.export.IIconCandidate;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Owned render candidates; native stacks never cross the worker boundary. */
abstract class IconCandidate implements IIconCandidate<IconCandidate> {

    private final String context;

    private IconCandidate(@NotNull String context) {
        this.context = context;
    }

    static @Nullable IconCandidate item(@NotNull String context, @NotNull ItemStack stack) {
        return stack.isEmpty() ? null : new ItemCandidate(context, stack);
    }

    static @NotNull IconCandidate fluid(@NotNull String context, @NotNull FluidStack stack) {
        return new FluidCandidate(context, stack.copy());
    }

    @Override
    public final @NotNull String context() {
        return context;
    }

    abstract void render(@NotNull LegacyIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws LegacyIconRenderer.RenderFailure;

    private static final class ItemCandidate extends IconCandidate {

        private final ItemStack stack;
        private final int metadata;
        private final int sourceMaxDamage;
        private final @Nullable NBTTagCompound tag;
        private final @Nullable NBTTagCompound capabilities;
        private final StableKey key;

        private ItemCandidate(@NotNull String context, @NotNull ItemStack source) {
            super(context);
            // Forge exposes all capabilities only through its native item codec. Snapshot before
            // copy invokes capability deserialization, which a mod may use to normalize state.
            NBTTagCompound caps = (NBTTagCompound) source.writeToNBT(new NBTTagCompound())
                .getTag("ForgeCaps");
            capabilities = caps == null ? null : caps.copy();
            NBTTagCompound sourceTag = source.getTagCompound();
            tag = sourceTag == null ? null : sourceTag.copy();
            metadata = source.getItemDamage();
            key = LegacyItemIdentity.encode(source.getItem(), metadata, tag, capabilities);
            sourceMaxDamage = !source.getItem()
                .getHasSubtypes() && source.getItem()
                    .isDamageable() ? source.getItem()
                        .getMaxDamage(source) : 0;
            stack = source.copy();
            stack.setCount(1);
        }

        @Override
        public @NotNull StableKey key() {
            return key;
        }

        @Override
        public boolean sameIdentity(@NotNull IconCandidate other) {
            return other instanceof ItemCandidate item && stack.getItem() == item.stack.getItem()
                && metadata == item.metadata
                && Objects.equals(tag, item.tag)
                && Objects.equals(capabilities, item.capabilities);
        }

        @Override
        public @NotNull IconCandidate baseline() {
            return new ItemCandidate(
                context() + "/base",
                LegacyIconBaseline.item(stack.getItem(), metadata, sourceMaxDamage));
        }

        @Override
        void render(@NotNull LegacyIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
            throws LegacyIconRenderer.RenderFailure {
            renderer.item(stack.copy(), key, captures);
        }
    }

    private static final class FluidCandidate extends IconCandidate {

        private final FluidStack stack;

        private FluidCandidate(@NotNull String context, @NotNull FluidStack stack) {
            super(context);
            this.stack = stack;
        }

        @Override
        public @NotNull StableKey key() {
            return LegacyItemIdentity.encode(stack);
        }

        @Override
        public boolean sameIdentity(@NotNull IconCandidate other) {
            return other instanceof FluidCandidate fluid && stack.getFluid() == fluid.stack.getFluid()
                && Platform.itemComparisons()
                    .isNbtTagEqual(stack.tag, fluid.stack.tag);
        }

        @Override
        public @NotNull IconCandidate baseline() {
            return new FluidCandidate(context() + "/base", new FluidStack(stack.getFluid(), Fluid.BUCKET_VOLUME));
        }

        @Override
        void render(@NotNull LegacyIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
            throws LegacyIconRenderer.RenderFailure {
            renderer.fluid(stack.copy(), key, captures);
        }
    }
}
