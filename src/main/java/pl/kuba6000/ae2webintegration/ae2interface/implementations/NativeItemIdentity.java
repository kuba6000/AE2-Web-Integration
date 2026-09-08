package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.NotNull;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Exact native AE key identity, including persistent addon data supported by native codecs. */
public final class NativeItemIdentity {

    private NativeItemIdentity() {}

    public static @NotNull StableKey getKey(@NotNull AEKey key) {
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
        HolderLookup.Provider registries = registries();
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
        for (var entry : patch.entrySet()) {
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
