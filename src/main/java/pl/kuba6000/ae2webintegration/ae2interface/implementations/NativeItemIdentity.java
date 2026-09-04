package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;

import net.minecraft.nbt.CompoundTag;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;

/** Exact built-in AE key identity; never reads or serializes inventory quantities. */
public final class NativeItemIdentity {

    private NativeItemIdentity() {}

    public static byte[] bytes(AEKey key) throws IOException {
        CompoundTag tag = encode(key);
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
        CompoundTag tag = encode(key);
        AEKey copy = key instanceof AEItemKey ? AEItemKey.fromTag(tag) : AEFluidKey.fromTag(tag);
        if (copy == null || !key.equals(copy)) {
            throw new UnsupportedOperationException("Native identity does not survive serialization");
        }
        return copy;
    }

    private static CompoundTag encode(AEKey key) throws IOException {
        CanonicalNbt.Budget budget = headerBudget(key);
        // The generic wrapper contains only fixed field names plus the already bounded registry identifier.
        budget.add(128);
        budget.text(
            key.getId()
                .toString());
        if (key instanceof AEItemKey item) {
            if (item.getTag() != null) CanonicalNbt.measure(item.getTag(), budget, 0);
            CompoundTag caps = capabilities(item);
            if (caps != null) CanonicalNbt.measure(caps, budget, 0);
        } else if (key instanceof AEFluidKey fluid) {
            if (fluid.getTag() != null) CanonicalNbt.measure(fluid.getTag(), budget, 0);
        }
        return key.toTagGeneric();
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

    private static CompoundTag capabilities(AEItemKey key) {
        try {
            Object interned = CapabilityFields.CAPS.get(key);
            return (CompoundTag) CapabilityFields.TAG.get(interned);
        } catch (ReflectiveOperationException | NullPointerException exception) {
            throw new UnsupportedOperationException("Cannot inspect AE item capabilities", exception);
        }
    }

    // AE2's private nested InternedTag cannot be named in a typed Mixin accessor signature.
    // Read these two pinned AE2 fields directly so toTagGeneric cannot copy an unbounded capability tag first.
    private static final class CapabilityFields {

        private static final Field CAPS = field(AEItemKey.class, "internedCaps");
        private static final Field TAG = CAPS == null ? null : field(CAPS.getType(), "tag");

        private static Field field(Class<?> owner, String name) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return null;
            }
        }
    }
}
