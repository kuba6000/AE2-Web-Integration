package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagString;

import appeng.api.storage.data.IAEStack;
import appeng.fluids.util.AEFluidStack;
import appeng.util.item.AEItemStack;
import pl.kuba6000.ae2webintegration.ae2interface.mixins.accessors.IdentityAEFluidStackAccessor;
import pl.kuba6000.ae2webintegration.ae2interface.mixins.accessors.IdentityNBTLongArrayAccessor;
import pl.kuba6000.ae2webintegration.core.identity.IdentityLimitException;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Canonical native identity only; amounts, crafting flags and display data never enter these bytes. */
public final class LegacyItemIdentity {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 65536;

    private LegacyItemIdentity() {}

    public static byte[] encode(IAEStack<?> stack) throws IOException {
        if (stack instanceof AEItemStack) {
            AEItemStack item = (AEItemStack) stack;
            requireSupportedCapabilities(item);
            return encode(
                "item",
                item.getItem()
                    .getRegistryName() == null ? null
                        : item.getItem()
                            .getRegistryName()
                            .toString(),
                item.getItemDamage(),
                item.getDefinition()
                    .getTagCompound());
        }
        if (stack instanceof AEFluidStack) {
            AEFluidStack fluid = (AEFluidStack) stack;
            NBTTagCompound tag = ((IdentityAEFluidStackAccessor) (Object) fluid).web$getIdentityTag();
            // AE2UEL's fluid equality collapses an empty secondary compound to absence.
            return encode(
                "fluid",
                fluid.getFluid()
                    .getName(),
                0,
                tag == null || tag.isEmpty() ? null : tag);
        }
        throw new UnsupportedOperationException("Unsupported legacy resource identity");
    }

    public static IAEKey copy(IAEStack<?> stack) throws IOException {
        // Item copies share AE2's interned identity, while fluid copies duplicate their native tag.
        if (stack instanceof AEItemStack) {
            requireSupportedCapabilities((AEItemStack) stack);
        } else {
            encode(stack);
        }
        IAEStack<?> result = stack.copy();
        result.reset();
        return (IAEKey) result;
    }

    private static void requireSupportedCapabilities(AEItemStack item) {
        // EMPTY has no dispatcher; Forge checks writer count without invoking any serializer.
        if (!item.getDefinition()
            .areCapsCompatible(ItemStack.EMPTY)) {
            throw new UnsupportedOperationException("Serializable capability identity needs a verified codec");
        }
    }

    private static byte[] encode(String kind, String registry, int metadata, NBTTagCompound secondary)
        throws IOException {
        if (registry == null || registry.isEmpty()) {
            throw new UnsupportedOperationException("Resource has no registered identity");
        }
        LimitedOutput bytes = new LimitedOutput();
        DataOutputStream output = new DataOutputStream(bytes);
        writeText(output, kind);
        writeText(output, registry);
        output.writeInt(metadata);
        output.writeByte(secondary == null ? 0 : 1);
        if (secondary != null) new Tags(output, bytes, "fluid".equals(kind)).write(secondary, 0, true);
        return bytes.toByteArray();
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        if (value.length() > StableItemKey.MAX_IDENTITY_BYTES) throw new IdentityLimitException();
        StableItemKey.writeText(output, value);
    }

    private static final class Tags {

        private final DataOutputStream output;
        private final LimitedOutput bytes;
        private final boolean normalizeZero;
        private int nodes;

        private Tags(DataOutputStream output, LimitedOutput bytes, boolean normalizeZero) {
            this.output = output;
            this.bytes = bytes;
            this.normalizeZero = normalizeZero;
        }

        private void write(NBTBase tag, int depth, boolean includeType) throws IOException {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw new IdentityLimitException();
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
                    bytes.ensure(4L + byteArray.length);
                    output.writeInt(byteArray.length);
                    output.write(byteArray);
                    break;
                case 8:
                    writeText(output, ((NBTTagString) tag).getString());
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
                        writeText(output, name);
                        write(compound.getTag(name), depth + 1, true);
                    }
                    break;
                case 11:
                    int[] intArray = ((NBTTagIntArray) tag).getIntArray();
                    bytes.ensure(4L + 4L * intArray.length);
                    output.writeInt(intArray.length);
                    for (int value : intArray) output.writeInt(value);
                    break;
                case 12:
                    long[] longArray = ((IdentityNBTLongArrayAccessor) (NBTTagLongArray) tag).web$getIdentityData();
                    bytes.ensure(4L + 8L * longArray.length);
                    output.writeInt(longArray.length);
                    for (long value : longArray) output.writeLong(value);
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported NBT identity type: " + type);
            }
        }

        private void checkChildren(int count) throws IdentityLimitException {
            if (count < 0 || count > MAX_NODES - nodes) throw new IdentityLimitException();
        }
    }

    private static final class LimitedOutput extends OutputStream {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private void ensure(long length) throws IdentityLimitException {
            if (length < 0 || length > StableItemKey.MAX_IDENTITY_BYTES - buffer.size()) {
                throw new IdentityLimitException();
            }
        }

        @Override
        public void write(int value) throws IOException {
            ensure(1);
            buffer.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            ensure(length);
            buffer.write(values, offset, length);
        }

        private byte[] toByteArray() {
            return buffer.toByteArray();
        }
    }
}
