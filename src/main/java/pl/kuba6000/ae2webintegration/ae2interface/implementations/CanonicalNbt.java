package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.*;

import pl.kuba6000.ae2webintegration.core.identity.IdentityLimitException;
import pl.kuba6000.ae2webintegration.core.identity.StableItemKey;

/** Bounded traversal of native tag data before copying or allocating its canonical representation. */
final class CanonicalNbt {

    private CanonicalNbt() {}

    static final class Budget {

        private long bytes;
        private int nodes;

        void add(long amount) throws IOException {
            bytes += amount;
            if (amount < 0 || bytes > StableItemKey.MAX_IDENTITY_BYTES) throw new IdentityLimitException();
        }

        void node(int depth) throws IOException {
            if (depth > 64 || ++nodes > 65536) throw new IdentityLimitException();
        }

        void text(String value) throws IOException {
            add(4);
            if (value.length() > StableItemKey.MAX_IDENTITY_BYTES) throw new IdentityLimitException();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                        throw new IOException("Malformed identity text");
                    }
                    add(4);
                } else if (Character.isLowSurrogate(c)) {
                    throw new IOException("Malformed identity text");
                } else {
                    add(c < 128 ? 1 : c < 2048 ? 2 : 3);
                }
            }
        }
    }

    static void measure(Tag tag, Budget budget, int depth) throws IOException {
        budget.add(1);
        measurePayload(tag, budget, depth);
    }

    private static void measurePayload(Tag tag, Budget budget, int depth) throws IOException {
        budget.node(depth);
        switch (tag.getId()) {
            case Tag.TAG_BYTE:
                budget.add(1);
                break;
            case Tag.TAG_SHORT:
                budget.add(2);
                break;
            case Tag.TAG_INT:
                budget.add(4);
                break;
            case Tag.TAG_LONG:
                budget.add(8);
                break;
            case Tag.TAG_FLOAT:
                float f = ((NumericTag) tag).getAsFloat();
                if (!Float.isFinite(f) || Float.floatToRawIntBits(f) == Integer.MIN_VALUE) {
                    throw new UnsupportedOperationException("Noncanonical floating-point identity");
                }
                budget.add(4);
                break;
            case Tag.TAG_DOUBLE:
                double d = ((NumericTag) tag).getAsDouble();
                if (!Double.isFinite(d) || Double.doubleToRawLongBits(d) == Long.MIN_VALUE) {
                    throw new UnsupportedOperationException("Noncanonical floating-point identity");
                }
                budget.add(8);
                break;
            case Tag.TAG_BYTE_ARRAY:
                budget.add(4L + ((ByteArrayTag) tag).getAsByteArray().length);
                break;
            case Tag.TAG_STRING:
                budget.text(tag.getAsString());
                break;
            case Tag.TAG_INT_ARRAY:
                budget.add(4L + 4L * ((IntArrayTag) tag).getAsIntArray().length);
                break;
            case Tag.TAG_LONG_ARRAY:
                budget.add(4L + 8L * ((LongArrayTag) tag).getAsLongArray().length);
                break;
            case Tag.TAG_LIST:
                ListTag list = (ListTag) tag;
                budget.add(5);
                if (list.size() > 65536) throw new IdentityLimitException();
                for (Tag element : list) measurePayload(element, budget, depth + 1);
                break;
            case Tag.TAG_COMPOUND:
                CompoundTag compound = (CompoundTag) tag;
                budget.add(4);
                if (compound.size() > 65536) throw new IdentityLimitException();
                for (String name : compound.getAllKeys()) {
                    budget.text(name);
                    measure(compound.get(name), budget, depth + 1);
                }
                break;
            default:
                throw new UnsupportedOperationException("Unsupported native tag type");
        }
    }

    static void write(Tag tag, DataOutput output) throws IOException {
        output.writeByte(tag.getId());
        writePayload(tag, output);
    }

    private static void writePayload(Tag tag, DataOutput output) throws IOException {
        switch (tag.getId()) {
            case Tag.TAG_BYTE:
                output.writeByte(((NumericTag) tag).getAsByte());
                break;
            case Tag.TAG_SHORT:
                output.writeShort(((NumericTag) tag).getAsShort());
                break;
            case Tag.TAG_INT:
                output.writeInt(((NumericTag) tag).getAsInt());
                break;
            case Tag.TAG_LONG:
                output.writeLong(((NumericTag) tag).getAsLong());
                break;
            case Tag.TAG_FLOAT:
                output.writeFloat(((NumericTag) tag).getAsFloat());
                break;
            case Tag.TAG_DOUBLE:
                output.writeDouble(((NumericTag) tag).getAsDouble());
                break;
            case Tag.TAG_STRING:
                StableItemKey.writeText(output, tag.getAsString());
                break;
            case Tag.TAG_BYTE_ARRAY:
                byte[] bytes = ((ByteArrayTag) tag).getAsByteArray();
                output.writeInt(bytes.length);
                output.write(bytes);
                break;
            case Tag.TAG_INT_ARRAY:
                int[] ints = ((IntArrayTag) tag).getAsIntArray();
                output.writeInt(ints.length);
                for (int value : ints) output.writeInt(value);
                break;
            case Tag.TAG_LONG_ARRAY:
                long[] longs = ((LongArrayTag) tag).getAsLongArray();
                output.writeInt(longs.length);
                for (long value : longs) output.writeLong(value);
                break;
            case Tag.TAG_LIST:
                ListTag list = (ListTag) tag;
                output.writeByte(list.isEmpty() ? Tag.TAG_END : list.getElementType());
                output.writeInt(list.size());
                for (Tag element : list) writePayload(element, output);
                break;
            case Tag.TAG_COMPOUND:
                CompoundTag compound = (CompoundTag) tag;
                List<String> names = new ArrayList<>(compound.getAllKeys());
                Collections.sort(names);
                output.writeInt(names.size());
                for (String name : names) {
                    StableItemKey.writeText(output, name);
                    write(compound.get(name), output);
                }
                break;
            default:
                throw new UnsupportedOperationException("Unsupported native tag type");
        }
    }
}
