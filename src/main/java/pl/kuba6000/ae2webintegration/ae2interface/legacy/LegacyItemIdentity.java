package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Set;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTBase.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import appeng.api.storage.data.IAEStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.registry.GameRegistry;
import pl.kuba6000.ae2webintegration.ae2interface.mixins.accessors.IdentityNBTListAccessor;
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
            GameRegistry.UniqueIdentifier name = GameRegistry.findUniqueIdentifierFor(item.getItem());
            return encode(
                "item",
                name == null ? null : name.toString(),
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

    public static IAEKey copy(IAEStack<?> stack) throws IOException {
        // Bound native tag traversal before an item reconstruction can copy it.
        encode(stack);
        IAEStack<?> result = stack instanceof AEItemStack ? AEItemStack.create(((AEItemStack) stack).getItemStack())
            : stack.copy();
        result.reset();
        return (IAEKey) result;
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
                    bytes.ensure(4L + byteArray.length);
                    output.writeInt(byteArray.length);
                    output.write(byteArray);
                    break;
                case 8:
                    writeText(output, ((NBTTagString) tag).func_150285_a_());
                    break;
                case 9:
                    NBTTagList list = (NBTTagList) tag;
                    int length = list.tagCount();
                    checkChildren(length);
                    int elementType = list.func_150303_d();
                    if (elementType < 0 || elementType > 11 || elementType == 0 && length != 0) {
                        throw new UnsupportedOperationException("Invalid NBT list type");
                    }
                    output.writeByte(elementType);
                    output.writeInt(length);
                    for (int index = 0; index < length; index++) {
                        NBTBase child = ((IdentityNBTListAccessor) list).web$getIdentityElements()
                            .get(index);
                        if (child.getId() != elementType) {
                            throw new UnsupportedOperationException("Mixed NBT list");
                        }
                        write(child, depth + 1, false);
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
                        writeText(output, name);
                        write(compound.getTag(name), depth + 1, true);
                    }
                    break;
                case 11:
                    int[] intArray = ((NBTTagIntArray) tag).func_150302_c();
                    bytes.ensure(4L + 4L * intArray.length);
                    output.writeInt(intArray.length);
                    for (int value : intArray) output.writeInt(value);
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
