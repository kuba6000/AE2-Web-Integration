package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.List;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.util.Platform;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyIconBaseline;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** One owned native candidate; it never crosses the writer thread boundary. */
abstract class IconCandidate {

    final String context;

    IconCandidate(@NotNull String context) {
        this.context = context;
    }

    abstract @NotNull StableKey key();

    abstract boolean sameIdentity(@NotNull IconCandidate other);

    abstract @NotNull IconCandidate baseline();

    abstract void render(@NotNull LegacyIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
        throws LegacyIconRenderer.RenderFailure;

    static @NotNull IconCandidate item(@NotNull String context, @NotNull ItemStack stack) {
        if (stack.getItem() == null) throw new IllegalArgumentException("Catalogue item has no registered item");
        stack.stackSize = 1;
        return new ItemCandidate(context, stack);
    }

    static @NotNull IconCandidate fluid(@NotNull String context, @NotNull FluidStack stack) {
        return new FluidCandidate(context, stack);
    }

    private static final class ItemCandidate extends IconCandidate {

        private final ItemStack stack;
        // Captured by key() before admission; rendering starts only after duplicate comparison finishes.
        private @Nullable Item identityItem;
        private int identityMetadata;
        private @Nullable NBTTagCompound identityTag;

        private ItemCandidate(@NotNull String context, @NotNull ItemStack stack) {
            super(context);
            this.stack = stack;
        }

        @Override
        @NotNull
        StableKey key() {
            Item item = stack.getItem();
            int metadata = Items.blaze_rod.getDamage(stack);
            NBTTagCompound tag = LegacyItemIdentity.prepareItemTag(stack);
            StableKey key = LegacyItemIdentity.encode(item, metadata, tag);
            identityItem = item;
            identityMetadata = metadata;
            identityTag = tag;
            return key;
        }

        @Override
        boolean sameIdentity(@NotNull IconCandidate other) {
            if (!(other instanceof ItemCandidate item) || identityItem != item.identityItem
                || identityMetadata != item.identityMetadata) return false;
            return identityTag == item.identityTag || identityTag != null && item.identityTag != null
                && Platform.NBTEqualityTest(identityTag, item.identityTag);
        }

        @Override
        @NotNull
        IconCandidate baseline() {
            return item(context + "/base", LegacyIconBaseline.item(stack));
        }

        @Override
        void render(@NotNull LegacyIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
            throws LegacyIconRenderer.RenderFailure {
            renderer.item(stack, LegacyIconRenderer.ICON_SIZE, key, captures);
        }
    }

    private static final class FluidCandidate extends IconCandidate {

        private final FluidStack stack;

        private FluidCandidate(@NotNull String context, @NotNull FluidStack stack) {
            super(context);
            this.stack = stack;
        }

        @Override
        @NotNull
        StableKey key() {
            return LegacyItemIdentity.encode(stack);
        }

        @Override
        boolean sameIdentity(@NotNull IconCandidate other) {
            // Match AEFluidStack factory equality: fluid identity only, without amount or native tag.
            return other instanceof FluidCandidate fluid && stack.getFluid() == fluid.stack.getFluid();
        }

        @Override
        @NotNull
        IconCandidate baseline() {
            return fluid(context + "/base", LegacyIconBaseline.fluid(stack));
        }

        @Override
        void render(@NotNull LegacyIconRenderer renderer, @NotNull StableKey key, @NotNull List<Capture> captures)
            throws LegacyIconRenderer.RenderFailure {
            renderer.fluid(stack, LegacyIconRenderer.ICON_SIZE, key, captures);
        }
    }
}
