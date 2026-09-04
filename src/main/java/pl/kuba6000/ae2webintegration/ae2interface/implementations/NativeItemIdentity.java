package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;

/** Exact supported AE key identity; component codecs are preflighted before native encoding. */
public final class NativeItemIdentity {

    private NativeItemIdentity() {}

    public static byte[] bytes(AEKey key) throws IOException {
        HolderLookup.Provider registries = registries();
        CompoundTag tag = encode(key, registries);
        requireEqualCopy(key, tag, registries);
        CanonicalNbt.Budget budget = headerBudget(key);
        CanonicalNbt.measure(tag, budget, 0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        StableItemKey.writeText(output, kind(key));
        StableItemKey.writeText(
            output,
            key.getId()
                .toString());
        output.writeInt(0);
        output.writeByte(1);
        CanonicalNbt.write(tag, output);
        return bytes.toByteArray();
    }

    public static AEKey copy(AEKey key) throws IOException {
        HolderLookup.Provider registries = registries();
        return requireEqualCopy(key, encode(key, registries), registries);
    }

    private static CompoundTag encode(AEKey key, HolderLookup.Provider registries) throws IOException {
        CanonicalNbt.Budget budget = headerBudget(key);
        budget.add(128);
        budget.text(
            key.getId()
                .toString());
        ComponentIdentityPreflight.check(key, budget);
        CompoundTag tag = key.toTagGeneric(registries);
        CanonicalNbt.measure(tag, headerBudget(key), 0);
        return tag;
    }

    private static AEKey requireEqualCopy(AEKey key, CompoundTag tag, HolderLookup.Provider registries) {
        AEKey copy = key instanceof AEItemKey ? AEItemKey.fromTag(registries, tag)
            : AEFluidKey.fromTag(registries, tag);
        if (copy == null || !key.equals(copy)) {
            throw new UnsupportedOperationException("Native components do not survive serialization");
        }
        return copy;
    }

    private static HolderLookup.Provider registries() throws IOException {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) throw new IOException("No current server for native identity");
        return server.registryAccess();
    }

    private static CanonicalNbt.Budget headerBudget(AEKey key) throws IOException {
        CanonicalNbt.Budget budget = new CanonicalNbt.Budget();
        budget.text(kind(key));
        budget.text(
            key.getId()
                .toString());
        budget.add(5);
        return budget;
    }

    private static String kind(AEKey key) {
        if (key instanceof AEItemKey) return "item";
        if (key instanceof AEFluidKey) return "fluid";
        throw new UnsupportedOperationException("Unsupported AE key type");
    }
}
