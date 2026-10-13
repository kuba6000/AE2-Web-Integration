package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Exact native AE key identity, including persistent addon data supported by native codecs. */
@SuppressWarnings("UnstableApiUsage")
public final class NativeItemIdentity {

    private NativeItemIdentity() {}

    public static @NotNull StableKey getKey(@NotNull AEKey key) {
        CompoundTag tag = key.toTagGeneric();
        // The native codec allocates its tag first. Bounds cover only the subsequent traversal.
        return StableKey.create(sink -> {
            StableKey.writeText(sink, kind(key));
            StableKey.writeText(
                sink,
                key.getId()
                    .toString());
            sink.putInt(0);
            sink.putByte((byte) 1);
            CanonicalNbt.write(tag, sink);
        });
    }

    /** Encodes an existing nonempty stack and its captured Forge capabilities without an AE wrapper. */
    public static @NotNull StableKey getKey(@NotNull ItemStack stack, @Nullable CompoundTag caps) {
        return getStackKey(
            "item",
            AEKeyType.items()
                .getId()
                .toString(),
            BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString(),
            stack.getTag(),
            caps == null || caps.isEmpty() ? null : caps);
    }

    /** AEItemKey.dropSecondary removes both tags and capabilities, including native defaults. */
    public static @NotNull StableKey getBaseKey(@NotNull ItemStack stack) {
        return getStackKey(
            "item",
            AEKeyType.items()
                .getId()
                .toString(),
            BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString(),
            null,
            null);
    }

    public static @NotNull StableKey getKey(@NotNull FluidStack stack) {
        return getStackKey(
            "fluid",
            AEKeyType.fluids()
                .getId()
                .toString(),
            BuiltInRegistries.FLUID.getKey(stack.getFluid())
                .toString(),
            stack.getTag(),
            null);
    }

    public static @NotNull StableKey getBaseKey(@NotNull FluidStack stack) {
        return getStackKey(
            "fluid",
            AEKeyType.fluids()
                .getId()
                .toString(),
            BuiltInRegistries.FLUID.getKey(stack.getFluid())
                .toString(),
            null,
            null);
    }

    private static @NotNull StableKey getStackKey(@NotNull String kind, @NotNull String type, @NotNull String id,
        @Nullable CompoundTag tag, @Nullable CompoundTag caps) {
        return StableKey.create(sink -> {
            StableKey.writeText(sink, kind);
            StableKey.writeText(sink, id);
            sink.putInt(0);
            sink.putByte((byte) 1);
            CanonicalNbt.writeKey(type, id, tag, caps, sink);
        });
    }

    public static @NotNull AEKey copy(@NotNull AEKey key) {
        // Built-in AE keys retain immutable identity under AE2's read-only tag/stack contract.
        if (key instanceof AEItemKey || key instanceof AEFluidKey) return key;
        return decodeCopy(key, key.toTagGeneric());
    }

    private static @NotNull AEKey decodeCopy(@NotNull AEKey key, @NotNull CompoundTag tag) {
        AEKey copy = key.getType()
            .loadKeyFromTag(tag);
        if (copy == null) {
            throw new UnsupportedOperationException("Native identity could not be decoded");
        }
        return copy;
    }

    private static @NotNull String kind(@NotNull AEKey key) {
        if (key instanceof AEItemKey) return "item";
        if (key instanceof AEFluidKey) return "fluid";
        return "ae-key:" + key.getType()
            .getId();
    }
}
