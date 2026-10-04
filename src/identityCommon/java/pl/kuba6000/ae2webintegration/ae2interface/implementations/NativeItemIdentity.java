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

    public static @NotNull StableKey getKey(@NotNull AEKey key) {
        return getKey(key, registries());
    }

    /** Uses the owning world's registries for the same codec on clients and servers. */
    public static @NotNull StableKey getKey(@NotNull AEKey key, @NotNull HolderLookup.Provider registries) {
        if (key instanceof AEItemKey item) {
            checkPersistent(
                item.getReadOnlyStack()
                    .getComponentsPatch());
        } else if (key instanceof AEFluidKey fluid) {
            // FluidStack copies its component map shallowly; component values are immutable.
            checkPersistent(
                fluid.toStack(1)
                    .getComponentsPatch());
        }
        CompoundTag tag = key.toTagGeneric(registries);
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

    /** Encodes the native item directly without creating an AE key or copying its stack. */
    public static @NotNull StableKey getKey(@NotNull ItemStack stack, @NotNull HolderLookup.Provider registries) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Empty item has no identity");
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
            original == null ? null : original.getUnsafe());
    }

    /** Encodes the native fluid directly without creating an AE key or copying its stack. */
    public static @NotNull StableKey getKey(@NotNull FluidStack stack, @NotNull HolderLookup.Provider registries) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Empty fluid has no identity");
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

    private static @NotNull StableKey getKey(@NotNull String kind, @NotNull String id, @NotNull String type,
        @NotNull DataComponentPatch patch, @NotNull HolderLookup.Provider registries, @Nullable CompoundTag original) {
        checkPersistent(patch);
        // Keep native component codecs; only their patch output needs a materialized tag.
        Tag components = patch.isEmpty() ? null
            : DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), patch)
                .getOrThrow();
        return StableKey.create(sink -> {
            StableKey.writeText(sink, kind);
            StableKey.writeText(sink, id);
            sink.putInt(0);
            sink.putByte((byte) 1);
            CanonicalNbt.writeResource(type, id, components, original, sink);
        });
    }

    public static @NotNull AEKey copy(@NotNull AEKey key) {
        // Built-in AE keys retain immutable identity under AE2's read-only tag/stack contract.
        if (key instanceof AEItemKey || key instanceof AEFluidKey) return key;
        HolderLookup.Provider registries = registries();
        return decodeCopy(key, key.toTagGeneric(registries), registries);
    }

    private static @NotNull AEKey decodeCopy(@NotNull AEKey key, @NotNull CompoundTag tag,
        @NotNull HolderLookup.Provider registries) {
        AEKey copy = key.getType()
            .loadKeyFromTag(registries, tag);
        if (copy == null) {
            throw new UnsupportedOperationException("Native identity could not be decoded");
        }
        return copy;
    }

    private static void checkPersistent(@NotNull DataComponentPatch patch) {
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            if (entry.getKey()
                .isTransient()) {
                throw new UnsupportedOperationException("Transient component cannot be serialized as native identity");
            }
        }
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
