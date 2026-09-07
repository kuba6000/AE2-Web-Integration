package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Set;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTBase.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.hash.Funnels;
import com.google.common.hash.PrimitiveSink;

import appeng.api.storage.data.IAEStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.registry.GameData;
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
            return encode(
                "item",
                GameData.getItemRegistry()
                    .getNameForObject(item.getItem()),
                item.getItemDamage(),
                (NBTTagCompound) item.getTagCompound());
        }
        if (stack instanceof AEFluidStack) {
            AEFluidStack fluid = (AEFluidStack) stack;
            return encode(
                "fluid",
                fluid.getFluid()
                    .getName(),
                0,
                (NBTTagCompound) fluid.getTagCompound());
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
                if (secondary != null) new Tags(output, sink, "fluid".equals(kind)).write(secondary, 0, true, false);
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

        private void write(NBTBase tag, int depth, boolean includeType, boolean owned) throws IOException {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES)
                throw new IllegalArgumentException("NBT identity exceeds structural limits");
            int type = tag.getId();
            if (includeType) output.writeByte(type);
            switch (type) {
                case 1:
                    output.writeByte(((NBTPrimitive) tag).func_150290_f());
                    break;
                case 2:
                    output.writeShort(((NBTPrimitive) tag).func_150289_e());
                    break;
                case 3:
                    output.writeInt(((NBTPrimitive) tag).func_150287_d());
                    break;
                case 4:
                    output.writeLong(((NBTPrimitive) tag).func_150291_c());
                    break;
                case 5:
                    float floatValue = ((NBTPrimitive) tag).func_150288_h();
                    if (Float.isNaN(floatValue)) throw new UnsupportedOperationException("NaN identity");
                    if (!normalizeZero && Float.floatToRawIntBits(floatValue) == Integer.MIN_VALUE) {
                        throw new UnsupportedOperationException("Negative-zero item identity");
                    }
                    output.writeFloat(floatValue == 0 ? 0 : floatValue);
                    break;
                case 6:
                    double doubleValue = ((NBTPrimitive) tag).func_150286_g();
                    if (Double.isNaN(doubleValue)) throw new UnsupportedOperationException("NaN identity");
                    if (!normalizeZero && Double.doubleToRawLongBits(doubleValue) == Long.MIN_VALUE) {
                        throw new UnsupportedOperationException("Negative-zero item identity");
                    }
                    output.writeDouble(doubleValue == 0 ? 0 : doubleValue);
                    break;
                case 7:
                    byte[] byteArray = ((NBTTagByteArray) tag).func_150292_c();
                    output.writeInt(byteArray.length);
                    output.write(byteArray);
                    break;
                case 8:
                    StableItemKey.writeText(sink, ((NBTTagString) tag).func_150285_a_());
                    break;
                case 9:
                    NBTTagList list = (NBTTagList) tag;
                    int length = list.tagCount();
                    checkChildren(length);
                    // GTNH's AE2 intern equality ignores the retained type of an empty item list.
                    int elementType = length == 0 && !normalizeZero ? 0 : list.func_150303_d();
                    if (elementType < 0 || elementType > 11 || elementType == 0 && length != 0) {
                        throw new UnsupportedOperationException("Invalid NBT list type");
                    }
                    output.writeByte(elementType);
                    output.writeInt(length);
                    if (length == 0) break;
                    if (elementType == 5 || elementType == 6
                        || elementType == 8
                        || elementType == 10
                        || elementType == 11) {
                        for (int index = 0; index < length; index++) {
                            write(publicListElement(list, index, elementType), depth + 1, false, owned);
                        }
                    } else {
                        // Only list types without public getters need a detached subtree. Once
                        // owned, recursively consume that same copy instead of copying nested lists.
                        NBTTagList detached = owned ? list : (NBTTagList) list.copy();
                        NBTBase[] children = new NBTBase[length];
                        for (int index = length - 1; index >= 0; index--) children[index] = detached.removeTag(index);
                        for (NBTBase child : children) {
                            if (child.getId() != elementType) {
                                throw new UnsupportedOperationException("Mixed NBT list");
                            }
                            write(child, depth + 1, false, true);
                        }
                    }
                    break;
                case 10:
                    NBTTagCompound compound = (NBTTagCompound) tag;
                    Set<String> keys = compound.func_150296_c();
                    checkChildren(keys.size());
                    String[] names = keys.toArray(new String[keys.size()]);
                    Arrays.sort(names);
                    output.writeInt(names.length);
                    for (String name : names) {
                        StableItemKey.writeText(sink, name);
                        write(compound.getTag(name), depth + 1, true, owned);
                    }
                    break;
                case 11:
                    int[] intArray = ((NBTTagIntArray) tag).func_150302_c();
                    output.writeInt(intArray.length);
                    for (int value : intArray) output.writeInt(value);
                    break;

                default:
                    throw new UnsupportedOperationException("Unsupported NBT identity type: " + type);
            }
        }

        private static NBTBase publicListElement(NBTTagList list, int index, int type) {
            switch (type) {
                case 5:
                    return new NBTTagFloat(list.func_150308_e(index));
                case 6:
                    return new NBTTagDouble(list.func_150309_d(index));
                case 8:
                    return new NBTTagString(list.getStringTagAt(index));
                case 10:
                    return list.getCompoundTagAt(index);
                case 11:
                    return new NBTTagIntArray(list.func_150306_c(index));
                default:
                    throw new IllegalArgumentException("NBT list type has no public getter");
            }
        }

        private void checkChildren(int count) {
            if (count < 0 || count > MAX_NODES - nodes)
                throw new IllegalArgumentException("NBT identity exceeds structural limits");
        }
    }
}
