package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Set;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.hash.Funnels;
import com.google.common.hash.PrimitiveSink;

import appeng.api.storage.data.IAEStack;
import appeng.fluids.util.AEFluidStack;
import appeng.util.item.AEItemStack;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Canonical native identity only; amounts, crafting flags and display data never enter these bytes. */
public final class LegacyItemIdentity {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 65536;

    private LegacyItemIdentity() {}

    public static @NotNull StableItemKey encode(@NotNull IAEStack<?> stack) {
        if (stack instanceof AEItemStack) {
            AEItemStack item = (AEItemStack) stack;
            NBTTagCompound identity = item.getDefinition()
                .writeToNBT(new NBTTagCompound());
            // Amount is presentation state; the full-width damage is framed separately below.
            identity.removeTag("Count");
            identity.removeTag("id");
            identity.removeTag("Damage");
            return encode(
                "item",
                item.getItem()
                    .getRegistryName() == null ? null
                        : item.getItem()
                            .getRegistryName()
                            .toString(),
                item.getItemDamage(),
                identity);
        }
        if (stack instanceof AEFluidStack) {
            AEFluidStack fluid = (AEFluidStack) stack;
            NBTTagCompound serialized = new NBTTagCompound();
            fluid.writeToNBT(serialized);
            NBTTagCompound tag = serialized.getCompoundTag("Tag");
            // AE2UEL's fluid equality collapses an empty secondary compound to absence.
            return encode(
                "fluid",
                fluid.getFluid()
                    .getName(),
                0,
                tag.isEmpty() ? null : tag);
        }
        throw new UnsupportedOperationException("Unsupported legacy resource identity");
    }

    public static @NotNull IAEKey copy(@NotNull IAEStack<?> stack) {
        if (!(stack instanceof AEItemStack) && !(stack instanceof AEFluidStack)) {
            throw new UnsupportedOperationException("Unsupported legacy resource identity");
        }
        // Follow AE2's native identity-sharing contract; only amount/crafting state is reset.
        IAEStack<?> result = stack.copy();
        result.reset();
        return (IAEKey) result;
    }

    private static StableItemKey encode(String kind, @Nullable String registry, int metadata,
        @Nullable NBTTagCompound secondary) {
        if (registry == null || registry.isEmpty()) {
            throw new UnsupportedOperationException("Resource has no registered identity");
        }
        return StableItemKey.create(sink -> {
            DataOutputStream output = new DataOutputStream(Funnels.asOutputStream(sink));
            try {
                StableItemKey.writeText(sink, kind);
                StableItemKey.writeText(sink, registry);
                output.writeInt(metadata);
                output.writeByte(secondary == null ? 0 : 1);
                if (secondary != null) new Tags(output, sink, "fluid".equals(kind)).write(secondary, 0, true);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    private static final class Tags {

        private final DataOutputStream output;
        private final PrimitiveSink sink;
        private final boolean normalizeZero;
        private int nodes;

        private Tags(DataOutputStream output, PrimitiveSink sink, boolean normalizeZero) {
            this.output = output;
            this.sink = sink;
            this.normalizeZero = normalizeZero;
        }

        private void write(NBTBase tag, int depth, boolean includeType) throws IOException {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES)
                throw new IllegalArgumentException("NBT identity exceeds structural limits");
            int type = tag.getId();
            if (includeType) output.writeByte(type);
            switch (type) {
                case 1:
                    output.writeByte(((NBTPrimitive) tag).getByte());
                    break;
                case 2:
                    output.writeShort(((NBTPrimitive) tag).getShort());
                    break;
                case 3:
                    output.writeInt(((NBTPrimitive) tag).getInt());
                    break;
                case 4:
                    output.writeLong(((NBTPrimitive) tag).getLong());
                    break;
                case 5:
                    float floatValue = ((NBTPrimitive) tag).getFloat();
                    if (Float.isNaN(floatValue)) throw new UnsupportedOperationException("NaN identity");
                    if (!normalizeZero && Float.floatToRawIntBits(floatValue) == Integer.MIN_VALUE) {
                        throw new UnsupportedOperationException("Negative-zero item identity");
                    }
                    output.writeFloat(floatValue == 0 ? 0 : floatValue);
                    break;
                case 6:
                    double doubleValue = ((NBTPrimitive) tag).getDouble();
                    if (Double.isNaN(doubleValue)) throw new UnsupportedOperationException("NaN identity");
                    if (!normalizeZero && Double.doubleToRawLongBits(doubleValue) == Long.MIN_VALUE) {
                        throw new UnsupportedOperationException("Negative-zero item identity");
                    }
                    output.writeDouble(doubleValue == 0 ? 0 : doubleValue);
                    break;
                case 7:
                    byte[] byteArray = ((NBTTagByteArray) tag).getByteArray();
                    output.writeInt(byteArray.length);
                    output.write(byteArray);
                    break;
                case 8:
                    StableItemKey.writeText(sink, ((NBTTagString) tag).getString());
                    break;
                case 9:
                    NBTTagList list = (NBTTagList) tag;
                    int length = list.tagCount();
                    checkChildren(length);
                    int elementType = list.getTagType();
                    if (elementType < 0 || elementType > 12 || elementType == 0 && length != 0) {
                        throw new UnsupportedOperationException("Invalid NBT list type");
                    }
                    output.writeByte(elementType);
                    output.writeInt(length);
                    for (int index = 0; index < length; index++) {
                        NBTBase child = list.get(index);
                        if (child.getId() != elementType) {
                            throw new UnsupportedOperationException("Mixed NBT list");
                        }
                        write(child, depth + 1, false);
                    }
                    break;
                case 10:
                    NBTTagCompound compound = (NBTTagCompound) tag;
                    Set<String> keys = compound.getKeySet();
                    checkChildren(keys.size());
                    String[] names = keys.toArray(new String[keys.size()]);
                    Arrays.sort(names);
                    output.writeInt(names.length);
                    for (String name : names) {
                        StableItemKey.writeText(sink, name);
                        write(compound.getTag(name), depth + 1, true);
                    }
                    break;
                case 11:
                    int[] intArray = ((NBTTagIntArray) tag).getIntArray();
                    output.writeInt(intArray.length);
                    for (int value : intArray) output.writeInt(value);
                    break;
                case 12:
                    // 1.12 exposes no long-array getter. Native serialization of this one leaf is
                    // deterministic and streams directly into the hash without an accessor or buffer.
                    NBTTagCompound leaf = new NBTTagCompound();
                    leaf.setTag("", tag);
                    CompressedStreamTools.write(leaf, output);
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported NBT identity type: " + type);
            }
        }

        private void checkChildren(int count) {
            if (count < 0 || count > MAX_NODES - nodes)
                throw new IllegalArgumentException("NBT identity exceeds structural limits");
        }
    }
}
