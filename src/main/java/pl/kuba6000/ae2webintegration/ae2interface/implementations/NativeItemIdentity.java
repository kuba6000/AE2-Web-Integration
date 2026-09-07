package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.NotNull;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;

/** Exact native AE key identity, including persistent addon data supported by native codecs. */
public final class NativeItemIdentity {

    private NativeItemIdentity() {}

    public static @NotNull StableItemKey getStableKey(@NotNull AEKey key) {
        CompoundTag tag = key.toTagGeneric();
        if (!(key instanceof AEItemKey) && !(key instanceof AEFluidKey)) {
            requireEqualCopy(key, tag);
        }
        // The native codec allocates its tag first. Bounds cover only the subsequent traversal.
        return StableItemKey.create(sink -> {
            StableItemKey.writeText(sink, kind(key));
            StableItemKey.writeText(
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
        return requireEqualCopy(key, key.toTagGeneric());
    }

    private static @NotNull AEKey requireEqualCopy(@NotNull AEKey key, @NotNull CompoundTag tag) {
        AEKey copy = key.getType()
            .loadKeyFromTag(tag);
        if (copy == null || !key.equals(copy)) {
            throw new UnsupportedOperationException("Native identity does not survive serialization");
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
