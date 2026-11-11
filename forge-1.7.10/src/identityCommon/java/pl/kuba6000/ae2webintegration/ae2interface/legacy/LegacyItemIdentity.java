package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import static net.minecraftforge.common.util.Constants.NBT.TAG_BYTE;
import static net.minecraftforge.common.util.Constants.NBT.TAG_BYTE_ARRAY;
import static net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND;
import static net.minecraftforge.common.util.Constants.NBT.TAG_DOUBLE;
import static net.minecraftforge.common.util.Constants.NBT.TAG_END;
import static net.minecraftforge.common.util.Constants.NBT.TAG_FLOAT;
import static net.minecraftforge.common.util.Constants.NBT.TAG_INT;
import static net.minecraftforge.common.util.Constants.NBT.TAG_INT_ARRAY;
import static net.minecraftforge.common.util.Constants.NBT.TAG_LIST;
import static net.minecraftforge.common.util.Constants.NBT.TAG_LONG;
import static net.minecraftforge.common.util.Constants.NBT.TAG_SHORT;
import static net.minecraftforge.common.util.Constants.NBT.TAG_STRING;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Set;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.hash.Funnels;
import com.google.common.hash.PrimitiveSink;

import appeng.api.storage.data.IAEStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.registry.GameData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Canonical native identity only; amounts, crafting flags and display data never enter these bytes. */
@SuppressWarnings("UnstableApiUsage")
public final class LegacyItemIdentity {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 65536;

    private LegacyItemIdentity() {}

    public static @Nullable StableKey encode(@NotNull ItemStack stack) {
        Item item = stack.getItem();
        if (item == null) return null;
        int metadata = Items.blaze_rod.getDamage(stack);
        NBTTagCompound tag = prepareItemTag(stack);
        return encode(item, metadata, tag);
    }

    /** Encodes captured item fields; tag must already follow prepareItemTag's root normalization. */
    public static @Nullable StableKey encode(@NotNull Item item, int metadata, @Nullable NBTTagCompound tag) {
        return encode(
            "item",
            GameData.getItemRegistry()
                .getNameForObject(item),
            metadata,
            tag);
    }

    /**
     * Call after capturing the item and raw metadata: native accessors may change the stack.
     * The returned tag is borrowed and must remain read-only while used for identity.
     */
    public static @Nullable NBTTagCompound prepareItemTag(@NotNull ItemStack stack) {
        stack.getItemDamageForDisplay();
        stack.getMaxDamage();
        NBTTagCompound tag = stack.getTagCompound();
        // AESharedNBT treats an empty root compound as absent, while preserving empty child tags.
        if (tag == null || tag.hasNoTags()) return null;
        // Mirror AESharedNBT's pre-interning accessors; keep its original, already-normalized tag reference.
        if (stack.isItemStackDamageable() && stack.getHasSubtypes()) stack.getItemDamage();
        return tag;
    }

    public static @Nullable StableKey encode(@NotNull FluidStack stack) {
        // The supported AEFluidStack factory discards FluidStack.tag; preserve its existing identity.
        return encode(
            "fluid",
            stack.getFluid()
                .getName(),
            0,
            null);
    }

    public static @Nullable StableKey encode(@NotNull IAEStack<?> stack) {
        if (stack instanceof AEItemStack item) {
            return encode(
                "item",
                GameData.getItemRegistry()
                    .getNameForObject(item.getItem()),
                item.getItemDamage(),
                (NBTTagCompound) item.getTagCompound());
        }
        if (stack instanceof AEFluidStack fluid) {
            return encode(
                "fluid",
                fluid.getFluid()
                    .getName(),
                0,
                (NBTTagCompound) fluid.getTagCompound());
        }
        return null;
    }

    public static @NotNull IAEStack<?> copy(@NotNull IAEStack<?> stack) {
        if (!(stack instanceof AEItemStack) && !(stack instanceof AEFluidStack)) {
            throw new UnsupportedOperationException("Unsupported legacy resource identity");
        }
        // Follow AE2's native identity-sharing contract; only amount/crafting state is reset.
        IAEStack<?> result = stack.copy();
        result.reset();
        return result;
    }

    private static @Nullable StableKey encode(String kind, @Nullable String registry, int metadata,
        @Nullable NBTTagCompound secondary) {
        if (registry == null || registry.isEmpty()) return null;
        return StableKey.createOptional(sink -> {
            DataOutputStream output = new DataOutputStream(Funnels.asOutputStream(sink));
            try {
                StableKey.writeText(sink, kind);
                StableKey.writeText(sink, registry);
                output.writeInt(metadata);
                output.writeByte(secondary == null ? 0 : 1);
                return secondary == null
                    || new Tags(output, sink, "fluid".equals(kind)).write(secondary, 0, true, false);
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

        private boolean write(NBTBase tag, int depth, boolean includeType, boolean owned) throws IOException {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES)
                throw new IllegalArgumentException("NBT identity exceeds structural limits");
            int type = tag.getId();
            if (includeType) output.writeByte(type);
            switch (type) {
                case TAG_END:
                    throw new IllegalArgumentException("Unexpected NBT end tag");
                case TAG_BYTE:
                    output.writeByte(((NBTBase.NBTPrimitive) tag).func_150290_f());
                    break;
                case TAG_SHORT:
                    output.writeShort(((NBTBase.NBTPrimitive) tag).func_150289_e());
                    break;
                case TAG_INT:
                    output.writeInt(((NBTBase.NBTPrimitive) tag).func_150287_d());
                    break;
                case TAG_LONG:
                    output.writeLong(((NBTBase.NBTPrimitive) tag).func_150291_c());
                    break;
                case TAG_FLOAT:
                    float floatValue = ((NBTBase.NBTPrimitive) tag).func_150288_h();
                    if (Float.isNaN(floatValue)) return false;
                    if (!normalizeZero && Float.floatToRawIntBits(floatValue) == Integer.MIN_VALUE) {
                        return false;
                    }
                    output.writeFloat(floatValue == 0 ? 0 : floatValue);
                    break;
                case TAG_DOUBLE:
                    double doubleValue = ((NBTBase.NBTPrimitive) tag).func_150286_g();
                    if (Double.isNaN(doubleValue)) return false;
                    if (!normalizeZero && Double.doubleToRawLongBits(doubleValue) == Long.MIN_VALUE) {
                        return false;
                    }
                    output.writeDouble(doubleValue == 0 ? 0 : doubleValue);
                    break;
                case TAG_BYTE_ARRAY:
                    byte[] byteArray = ((NBTTagByteArray) tag).func_150292_c();
                    output.writeInt(byteArray.length);
                    output.write(byteArray);
                    break;
                case TAG_STRING:
                    StableKey.writeText(sink, ((NBTTagString) tag).func_150285_a_());
                    break;
                case TAG_LIST:
                    NBTTagList list = (NBTTagList) tag;
                    int length = list.tagCount();
                    checkChildren(length);
                    // GTNH's AE2 intern equality ignores the retained type of an empty item list.
                    int elementType = length == 0 && !normalizeZero ? TAG_END : list.func_150303_d();
                    if (elementType == TAG_END && length != 0) {
                        throw new IllegalArgumentException("Invalid NBT list type");
                    }
                    if (elementType < TAG_END || elementType > TAG_INT_ARRAY) return false;
                    output.writeByte(elementType);
                    output.writeInt(length);
                    if (length == 0) break;
                    if (elementType == TAG_FLOAT || elementType == TAG_DOUBLE
                        || elementType == TAG_STRING
                        || elementType == TAG_COMPOUND
                        || elementType == TAG_INT_ARRAY) {
                        for (int index = 0; index < length; index++) {
                            if (!write(publicListElement(list, index, elementType), depth + 1, false, owned))
                                return false;
                        }
                    } else {
                        // Only list types without public getters need a detached subtree. Once
                        // owned, recursively consume that same copy instead of copying nested lists.
                        NBTTagList detached = owned ? list : (NBTTagList) list.copy();
                        NBTBase[] children = new NBTBase[length];
                        for (int index = length - 1; index >= 0; index--) children[index] = detached.removeTag(index);
                        for (NBTBase child : children) {
                            if (child.getId() != elementType) {
                                throw new IllegalArgumentException("Mixed NBT list");
                            }
                            if (!write(child, depth + 1, false, true)) return false;
                        }
                    }
                    break;
                case TAG_COMPOUND:
                    NBTTagCompound compound = (NBTTagCompound) tag;
                    Set<String> keys = compound.func_150296_c();
                    checkChildren(keys.size());
                    String[] names = keys.toArray(new String[keys.size()]);
                    Arrays.sort(names);
                    output.writeInt(names.length);
                    for (String name : names) {
                        StableKey.writeText(sink, name);
                        if (!write(compound.getTag(name), depth + 1, true, owned)) return false;
                    }
                    break;
                case TAG_INT_ARRAY:
                    int[] intArray = ((NBTTagIntArray) tag).func_150302_c();
                    output.writeInt(intArray.length);
                    for (int value : intArray) output.writeInt(value);
                    break;

                default:
                    return false;
            }
            return true;
        }

        private static NBTBase publicListElement(NBTTagList list, int index, int type) {
            return switch (type) {
                case TAG_FLOAT -> new NBTTagFloat(list.func_150308_e(index));
                case TAG_DOUBLE -> new NBTTagDouble(list.func_150309_d(index));
                case TAG_STRING -> new NBTTagString(list.getStringTagAt(index));
                case TAG_COMPOUND -> list.getCompoundTagAt(index);
                case TAG_INT_ARRAY -> new NBTTagIntArray(list.func_150306_c(index));
                default -> throw new IllegalArgumentException("NBT list type has no public getter");
            };
        }

        private void checkChildren(int count) {
            if (count < 0 || count > MAX_NODES - nodes)
                throw new IllegalArgumentException("NBT identity exceeds structural limits");
        }
    }
}
