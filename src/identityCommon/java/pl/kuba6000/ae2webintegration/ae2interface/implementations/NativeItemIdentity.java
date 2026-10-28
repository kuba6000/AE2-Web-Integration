package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.util.Map;
import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.core.definitions.AEItems;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Exact native AE key identity, including persistent addon data supported by native codecs. */
@SuppressWarnings("UnstableApiUsage")
public final class NativeItemIdentity {

    private NativeItemIdentity() {}

    public static @Nullable StableKey getKey(@NotNull AEKey key) {
        return getKey(key, registries());
    }

    /** Uses the owning world's registries for the same codec on clients and servers. */
    public static @Nullable StableKey getKey(@NotNull AEKey key, @NotNull HolderLookup.Provider registries) {
        if (key instanceof AEItemKey item) {
            if (!isPersistent(
                item.getReadOnlyStack()
                    .getComponentsPatch()))
                return null;
        } else if (key instanceof AEFluidKey fluid) {
            // FluidStack copies its component map shallowly; component values are immutable.
            if (!isPersistent(
                fluid.toStack(1)
                    .getComponentsPatch()))
                return null;
        }
        Tag tag = encodeTag(key, registries);
        // The native codec allocates its tag first. Bounds cover only the subsequent traversal.
        return StableKey.createOptional(sink -> {
            StableKey.writeText(sink, kind(key));
            StableKey.writeText(
                sink,
                key.getId()
                    .toString());
            sink.putInt(0);
            sink.putByte((byte) 1);
            return CanonicalNbt.write(tag, sink);
        });
    }

    /** Encodes the native item directly without creating an AE key or copying its stack. */
    public static @Nullable StableKey getKey(@NotNull ItemStack stack, @NotNull HolderLookup.Provider registries) {
        if (stack.isEmpty()) return null;
        CustomData original = stack.is(AEItems.MISSING_CONTENT.asItem())
            ? stack.get(AEComponents.MISSING_CONTENT_AEKEY_DATA)
            : null;
        return getKey(
            "item",
            BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString(),
            AEKeyType.items()
                .getId()
                .toString(),
            stack.getComponentsPatch(),
            registries,
            original == null ? null : original.copyTag());
    }

    /** Encodes the native fluid directly without creating an AE key or copying its stack. */
    public static @Nullable StableKey getKey(@NotNull FluidStack stack, @NotNull HolderLookup.Provider registries) {
        if (stack.isEmpty()) return null;
        return getKey(
            "fluid",
            BuiltInRegistries.FLUID.getKey(stack.getFluid())
                .toString(),
            AEKeyType.fluids()
                .getId()
                .toString(),
            stack.getComponentsPatch(),
            registries,
            null);
    }

    private static @Nullable StableKey getKey(@NotNull String kind, @NotNull String id, @NotNull String type,
        @NotNull DataComponentPatch patch, @NotNull HolderLookup.Provider registries, @Nullable CompoundTag original) {
        if (!isPersistent(patch)) return null;
        // Keep native component codecs; only their patch output needs a materialized tag.
        Tag components = patch.isEmpty() ? null
            : DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), patch)
                .getOrThrow();
        return StableKey.createOptional(sink -> {
            StableKey.writeText(sink, kind);
            StableKey.writeText(sink, id);
            sink.putInt(0);
            sink.putByte((byte) 1);
            return CanonicalNbt.writeResource(type, id, components, original, sink);
        });
    }

    public static @NotNull AEKey copy(@NotNull AEKey key) {
        // Built-in AE keys retain immutable identity under AE2's read-only tag/stack contract.
        if (key instanceof AEItemKey || key instanceof AEFluidKey) return key;
        HolderLookup.Provider registries = registries();
        return decodeCopy(key, encodeTag(key, registries), registries);
    }

    private static @NotNull AEKey decodeCopy(@NotNull AEKey key, @NotNull Tag tag,
        @NotNull HolderLookup.Provider registries) {
        return key.getType()
            .codec()
            .codec()
            .parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
            .getOrThrow();
    }

    private static @NotNull Tag encodeTag(@NotNull AEKey key, @NotNull HolderLookup.Provider registries) {
        return AEKey.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), key)
            .getOrThrow();
    }

    private static boolean isPersistent(@NotNull DataComponentPatch patch) {
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            if (entry.getKey()
                .isTransient()) {
                return false;
            }
        }
        return true;
    }

    private static @NotNull HolderLookup.Provider registries() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) throw new IllegalStateException("No current server for native identity");
        return server.registryAccess();
    }

    private static @NotNull String kind(@NotNull AEKey key) {
        if (key instanceof AEItemKey) return "item";
        if (key instanceof AEFluidKey) return "fluid";
        return "ae-key:" + key.getType()
            .getId();
    }
}
